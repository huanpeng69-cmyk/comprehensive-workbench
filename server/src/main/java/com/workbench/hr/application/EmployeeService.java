package com.workbench.hr.application;

import com.workbench.audit.application.AuditService;
import com.workbench.hr.domain.Employee;
import com.workbench.hr.repository.EmployeeRepository;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.WriteOwnershipResolver;
import com.workbench.security.CurrentUserHolder;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人员花名册服务。
 * 数据权限（阶段 2，ADR-M008）：先按单位隔离——只能看到/改动本单位（company_id）数据；
 * 本单位内 COMPANY 数据范围角色可见全部，PROJECT 数据范围角色仅可见本人项目部
 * （employee.project_id = 当前用户 projectId）。过滤在 SQL 层完成，禁止前端过滤。
 * 审计：新增/修改/删除/导入均留痕；敏感字段（身份证/手机号/月薪）不写入审计。
 */
@Service
public class EmployeeService {

  private final EmployeeRepository employeeRepository;
  private final OrgQueryPort orgQueryPort;
  private final DataScopeResolver dataScopeResolver;
  private final AuditService auditService;
  private final WriteOwnershipResolver writeOwnershipResolver;

  public EmployeeService(EmployeeRepository employeeRepository, OrgQueryPort orgQueryPort,
      DataScopeResolver dataScopeResolver, AuditService auditService,
      WriteOwnershipResolver writeOwnershipResolver) {
    this.employeeRepository = employeeRepository;
    this.orgQueryPort = orgQueryPort;
    this.dataScopeResolver = dataScopeResolver;
    this.auditService = auditService;
    this.writeOwnershipResolver = writeOwnershipResolver;
  }

  /**
   * 数据范围过滤的列表查询：
   * COMPANY → 本单位全部（company_id = 当前用户 companyId；companyId 缺失一律空列表，不放行全库）；
   * PROJECT → 仅本人项目部；无项目部归属 → 空列表（严格模式）。
   */
  @Transactional(readOnly = true)
  public List<Employee> list() {
    if (dataScopeResolver.hasCompanyScope()) {
      Long companyId = dataScopeResolver.currentCompanyId();
      return companyId == null ? List.of()
          : employeeRepository.findAllByCompanyIdOrderBySeqAscIdAsc(companyId);
    }
    Long projectId = dataScopeResolver.currentProjectId();
    if (projectId == null) {
      return List.of();
    }
    return employeeRepository.findByProjectIdOrderBySeqAscIdAsc(projectId);
  }

  @Transactional
  public Employee create(Employee incoming) {
    assertCompanyScope("新增员工");
    Long companyId = writeOwnershipResolver.requireCompanyId(null, "EMPLOYEE", "新增员工");
    resolveProject(incoming, companyId);
    incoming.setCompanyId(companyId);
    incoming.setId(null);
    if (incoming.getSeq() == null) {
      incoming.setSeq(0);
    }
    Employee saved = employeeRepository.save(incoming);
    auditService.log("EMPLOYEE_CREATE", "EMPLOYEE", String.valueOf(saved.getId()),
        "新增员工：" + saved.getName() + "（" + saved.getProject() + "）");
    return saved;
  }

  @Transactional
  public Employee update(Long id, Employee incoming) {
    Employee existing = employeeRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("员工不存在：" + id));
    assertCanModify(existing);
    resolveProject(incoming, existing.getCompanyId());
    copyEditableFields(existing, incoming);
    // 归属单位不可通过更新改写：copyEditableFields 不触碰 companyId（阶段 2，ADR-M008）
    Employee saved = employeeRepository.save(existing);
    auditService.log("EMPLOYEE_UPDATE", "EMPLOYEE", String.valueOf(saved.getId()),
        "修改员工：" + saved.getName());
    return saved;
  }

  @Transactional
  public void delete(Long id) {
    Employee existing = employeeRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("员工不存在：" + id));
    assertCanModify(existing);
    employeeRepository.delete(existing);
    auditService.log("EMPLOYEE_DELETE", "EMPLOYEE", String.valueOf(id),
        "删除员工：" + existing.getName());
  }

  /**
   * 花名册整体导入（Excel 解析结果覆盖式写入）。
   * 覆盖范围是<b>当前用户所属单位</b>（阶段 2 起不再影响全库全表）：先清空本单位花名册再整批写入，
   * 其他单位数据不动。仅 COMPANY 数据范围角色可执行。整体在一个事务内，失败即全部回滚。
   */
  @Transactional
  public int importReplace(List<Employee> members) {
    assertCompanyScope("导入花名册（覆盖本单位）");
    if (members == null) {
      throw new IllegalArgumentException("导入数据不能为空");
    }
    Long companyId = writeOwnershipResolver.requireCompanyId(null, "EMPLOYEE", "导入花名册");
    for (Employee m : members) {
      resolveProject(m, companyId);
      m.setCompanyId(companyId);
      m.setId(null);
      if (m.getSeq() == null) {
        m.setSeq(0);
      }
    }
    employeeRepository.deleteByCompanyId(companyId);
    employeeRepository.saveAll(members);
    auditService.log("EMPLOYEE_IMPORT", "EMPLOYEE", "-",
        "导入花名册 " + members.size() + " 条（覆盖式）");
    return members.size();
  }

  /**
   * COMPANY 数据范围守卫：单位级全量操作（新增/导入）仅限全公司数据范围角色。
   * 归属单位另由 {@link WriteOwnershipResolver} 解析，两者缺一不可。
   */
  private void assertCompanyScope(String action) {
    if (dataScopeResolver.hasCompanyScope()) {
      return;
    }
    auditService.log(CurrentUserHolder.currentUsername(), "ACCESS_DENIED", "EMPLOYEE", "-",
        "尝试执行需要全公司数据范围的操作：" + action);
    throw new IllegalStateException("该操作需要全公司数据权限");
  }

  /**
   * 写操作数据范围守卫：
   * ① 先过单位边界（阶段 2，ADR-M008）—— 只能操作本单位记录，COMPANY 数据范围也不例外；
   * ② PROJECT 数据范围用户只能修改本人项目部的员工；未分配项目部（project_id 为空）的员工仅
   * COMPANY 数据范围可改。越权尝试写审计（ACCESS_DENIED）并抛出异常。
   */
  private void assertCanModify(Employee existing) {
    if (!dataScopeResolver.canAccessCompany(existing.getCompanyId())) {
      auditService.log(CurrentUserHolder.currentUsername(), "ACCESS_DENIED", "EMPLOYEE",
          String.valueOf(existing.getId()),
          "尝试越权操作非本单位的员工：" + existing.getName());
      throw new IllegalStateException("无权操作其他单位的员工数据");
    }
    if (dataScopeResolver.canAccessProject(existing.getProjectId())) {
      return;
    }
    auditService.log(CurrentUserHolder.currentUsername(), "ACCESS_DENIED", "EMPLOYEE",
        String.valueOf(existing.getId()),
        "尝试越权修改非本项目部员工：" + existing.getName());
    throw new IllegalStateException("无权操作其他项目部的员工数据");
  }

  /** 按项目名称匹配 org_unit（type=PROJECT）；匹配不到则 projectId 置空。 */
  /**
   * 按「本单位 + 项目名称」匹配 org_unit（type=PROJECT）；匹配不到则 projectId 置空。
   *
   * <p>阶段 2 起组织单元名称不再全局唯一（各单位都有「天河项目部」等同名下级单位），故必须限定在
   * 目标单位内匹配，禁止跨单位命中（ADR-M008）。</p>
   */
  private void resolveProject(Employee e, Long companyId) {
    e.setProjectId(null);
    String name = e.getProject();
    if (name != null && !name.isBlank()) {
      orgQueryPort.findFirstByNameAndCompanyId(name.trim(), companyId)
          .ifPresent(org -> e.setProjectId(org.id()));
    }
  }

  private void copyEditableFields(Employee target, Employee src) {
    target.setSeq(nz(src.getSeq(), target.getSeq()));
    target.setName(src.getName());
    target.setGender(src.getGender());
    target.setIdCard(src.getIdCard());
    target.setPhone(src.getPhone());
    target.setAge(src.getAge());
    target.setJoinDate(src.getJoinDate());
    target.setEmploymentType(src.getEmploymentType());
    target.setEducation(src.getEducation());
    target.setSchool(src.getSchool());
    target.setMajor(src.getMajor());
    target.setTitle(src.getTitle());
    target.setPolitical(src.getPolitical());
    target.setPosition(src.getPosition());
    target.setAdminSeries(src.getAdminSeries());
    target.setProfSeries(src.getProfSeries());
    target.setSalaryMechanism(src.getSalaryMechanism());
    target.setSalaryGrade(src.getSalaryGrade());
    target.setMonthlyPay(src.getMonthlyPay());
    target.setFirstAdjust(src.getFirstAdjust());
    target.setProject(src.getProject());
    target.setStatus(src.getStatus());
    target.setCertificates(src.getCertificates());
    target.setContractRel(src.getContractRel());
    target.setCategory(src.getCategory());
    target.setProjectId(src.getProjectId());
  }

  private Integer nz(Integer v, Integer fallback) {
    return v != null ? v : fallback;
  }
}
