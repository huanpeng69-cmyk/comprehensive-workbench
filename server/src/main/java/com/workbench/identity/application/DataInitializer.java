package com.workbench.identity.application;

import com.workbench.identity.domain.OrgUnit;
import com.workbench.identity.domain.Role;
import com.workbench.identity.domain.User;
import com.workbench.identity.repository.OrgUnitRepository;
import com.workbench.identity.domain.Permission;
import com.workbench.identity.repository.PermissionRepository;
import com.workbench.identity.repository.RoleRepository;
import com.workbench.identity.repository.UserRepository;
import com.workbench.security.Permissions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示数据初始化：表为空时才写入，重复启动幂等。密码统一为演示口令（见仓库 README 的「演示账号」一节，仅演示环境）。
 *
 * <p>阶段 2（ADR-M008）起组织单元由迁移 {@code V10__multi_company.sql} 种下（每家公司 1 个 COMPANY
 * + 5 个 PROJECT + 7 个 DEPARTMENT），本类只补演示账号，落点与四个业务 Seeder 完全一致：
 * 「{@code type='COMPANY'} 中 id 最小」那家公司的第一个项目部。已有账号的库（开发库、生产库）
 * 整段空转，故本类只影响空库新装。</p>
 */
@Component
public class DataInitializer implements CommandLineRunner {

  private final OrgUnitRepository orgUnitRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final RoleRepository roleRepository;
  private final PermissionRepository permissionRepository;

  public DataInitializer(OrgUnitRepository orgUnitRepository, UserRepository userRepository,
      RoleRepository roleRepository, PasswordEncoder passwordEncoder,
      PermissionRepository permissionRepository) {
    this.orgUnitRepository = orgUnitRepository;
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.passwordEncoder = passwordEncoder;
    this.permissionRepository = permissionRepository;
  }

  @Override
  @Transactional
  public void run(String... args) {
    // 表为空时才写入演示数据（幂等）；管理员账号无论表是否为空都确保存在。
    if (userRepository.count() == 0) {
      seedDemoData();
    }
    ensureAdminAccount();
    // 每次启动都补授「AI 助手使用」权限点（幂等）：只能授给"全部现有角色"，而角色表只在启动期才确定，
    // 故不能写进 Flyway（ADR-M013）。少了这一步，升级后所有账号调 /api/ai/writing/agent 都会 403。
    grantAgentUseToAllRoles();
  }

  private void seedDemoData() {
    // 阶段 2（ADR-M008）：组织单元由迁移 V10 种下（每家公司 1 个 COMPANY + 5 个 PROJECT + 7 个 DEPARTMENT），
    // 本类不再自建公司/项目/部门，只补演示账号。两个原因：
    //  1) org_unit.company_id 在 V10 第 4 节收紧为 NOT NULL，而 COMPANY 行的 company_id = 自身，
    //     「先插入再回写」的两段保存已不可能（第一次 INSERT 就撞 NOT NULL）；
    //  2) 四个业务 Seeder 的归属单位锚点同为「type='COMPANY' 中 id 最小」，演示账号必须落在同一家单位，
    //     否则会出现「账号在一家公司、演示数据在另一家公司」的分家。
    OrgUnit company = orgUnitRepository.findFirstByTypeOrderByIdAsc("COMPANY")
        .orElseThrow(() -> new IllegalStateException("库中没有 COMPANY 组织单元，无法初始化演示账号"));
    List<OrgUnit> projects =
        orgUnitRepository.findByTypeAndCompanyIdOrderBySortNoAscIdAsc("PROJECT", company.getId());
    if (projects.isEmpty()) {
      throw new IllegalStateException(
          "公司「" + company.getName() + "」下没有 PROJECT 组织单元，无法初始化演示账号");
    }
    OrgUnit firstProject = projects.get(0);
    // V10 每家公司种 5 个项目部；结构被人工裁剪时退回第一个项目部，不让启动失败
    OrgUnit anotherProject = projects.size() > 1 ? projects.get(1) : firstProject;

    Role employee = ensureRole("EMPLOYEE", "普通员工", "PROJECT");
    Role pm = ensureRole("PROJECT_MANAGER", "项目经理", "PROJECT");
    Role hr = ensureRole("HR_ADMIN", "人力管理员", "COMPANY");
    Role finance = ensureRole("FINANCE", "财务", "COMPANY");
    ensureRole("PARTY_ADMIN", "党建管理员", "PROJECT");
    ensureRole("UNION_ADMIN", "工会管理员", "PROJECT");
    ensureRole("SYSTEM_ADMIN", "系统管理员", "COMPANY");
    // 接待流程审批人角色（ADR-M005）：V10 迁移已建，代码侧同样幂等，保证空库新装也能落齐。
    // 这三个角色码同时是接待流程 A/B/C 节点的指派角色（WorkflowDataSeeder），故必须授予对应演示审批人，
    // 否则该节点解析不到审批人会直接抛「未解析到审批人」。
    Role officeDirector = ensureRole("OFFICE_DIRECTOR", "办公室主任", "COMPANY");
    Role officeDeputy = ensureRole("OFFICE_DEPUTY", "办公室副主任", "COMPANY");
    Role deptLeader = ensureRole("DEPT_LEADER", "部门负责人", "PROJECT");
    // 慰问金流程「二级单位工会审批」节点的指派角色（V11__condolence_union_role.sql）：V8 曾把该节点
    // 写死成用户名 fangjing，用户名只在单位内唯一后按用户名派单会跨单位命中同名账号；V11 已把节点改为
    // ROLE/UNION_LEADER，故这里必须把演示审批人接到该角色，否则空库新装走到该节点会解析不到审批人而卡死。
    Role unionLeader = ensureRole("UNION_LEADER", "工会主席", "COMPANY");

    String hash = passwordEncoder.encode("Workbench@2026");
    saveUser("chenguodong", "陈国栋", hash, firstProject, firstProject.getId(), "正式职工", "13800000001",
        pm, officeDirector);
    saveUser("limingyuan", "李明远", hash, firstProject, firstProject.getId(), "正式职工", "13800000002",
        pm, officeDeputy);
    saveUser("fangjing", "方静", hash, firstProject, firstProject.getId(), "正式职工", "13800000015", hr,
        finance, unionLeader);
    saveUser("wanghaitao", "王海涛", hash, firstProject, firstProject.getId(), "正式职工", "13800000003",
        employee, deptLeader);
    saveUser("zhaoqiming", "赵启明", hash, firstProject, firstProject.getId(), "正式职工", "13800000004",
        employee);
    saveUser("wujunjie", "吴俊杰", hash, anotherProject, anotherProject.getId(), "正式职工", "13800000009",
        employee);
  }

  /**
   * 每启动都确保存在一个 SYSTEM_ADMIN 管理员账号（admin / Admin@2026），用于自助管理其他成员。
   * 幂等：账号已存在则跳过；若 SYSTEM_ADMIN 角色缺失则补建。
   */
  private void ensureAdminAccount() {
    Role adminRole = roleRepository.findByCode("SYSTEM_ADMIN")
        .orElseGet(() -> roleRepository.save(role("SYSTEM_ADMIN", "系统管理员", "COMPANY")));
    OrgUnit company = orgUnitRepository.findByTypeOrderBySortNo("COMPANY").stream()
        .findFirst()
        // company_id 非空：没有任何单位时无法归属管理员账号，宁可显式失败也不重复建号
        .orElseThrow(() -> new IllegalStateException("缺少 COMPANY 组织单元，无法确定管理员账号的归属单位"));
    Long orgId = company.getId();
    // 阶段 2 起用户名只在单位内唯一（ADR-M006），管理员账号按「单位 + 用户名」定位。
    Optional<User> existing = userRepository.findByCompanyIdAndUsername(orgId, "admin");
    if (existing.isPresent()) {
      // 自愈：账号已存在，但密码哈希非法（历史脏数据）或丢失管理员角色时，修正为可登录状态。
      User admin = existing.get();
      boolean repaired = false;
      if (admin.getPasswordHash() == null || !admin.getPasswordHash().startsWith("$2")) {
        admin.setPasswordHash(passwordEncoder.encode("Admin@2026"));
        repaired = true;
      }
      boolean hasAdminRole = admin.getRoles().stream()
          .anyMatch(r -> "SYSTEM_ADMIN".equals(r.getCode()));
      if (!hasAdminRole) {
        admin.getRoles().add(adminRole);
        repaired = true;
      }
      if (repaired) {
        userRepository.save(admin);
      }
      return;
    }
    User admin = User.builder()
        .username("admin")
        .name("系统管理员")
        .passwordHash(passwordEncoder.encode("Admin@2026"))
        .orgId(orgId)
        .projectId(orgId)
        .companyId(orgId)
        .employmentType("正式职工")
        .phone("13800000000")
        .enabled(true)
        .roles(new java.util.HashSet<>(java.util.List.of(adminRole)))
        .build();
    userRepository.save(admin);
    // 权限点与角色种子同源（ADR-M010）：V14 只在 Flyway 阶段建权限点字典，
    // 而角色行由本类写入，两者时机不同，授权必须放在这里才对新装库生效。
    grantDefinitionManage(adminRole);
  }

  /**
   * Permission code for workflow definition admin (ADR-M010).
   *
   * Deliberately a local literal instead of importing workflow's constant:
   * identity depending on workflow would break module boundaries
   * (ModuleBoundaryTest). Consistency is covered by SchemaBaselineIT.
   */
  private static final String PERMISSION_DEFINITION_MANAGE = "workflow:definition:manage";

  /**
   * 把「流程定义管理」权限点授给指定角色（ADR-M010）。幂等：已授则跳过。
   *
   * <p>放在这里而不是只靠 V14 的原因：V14 跑 Flyway 时 sys_role 还是空的（角色种子由本类写），
   * 那条 insert 匹配不到任何角色行；新装库的授权只能由本类在角色就绪后补。</p>
   */
  private void grantDefinitionManage(Role role) {
    Permission permission = permissionRepository
        .findByCode(PERMISSION_DEFINITION_MANAGE)
        .orElseGet(() -> permissionRepository.save(Permission.builder()
            .code(PERMISSION_DEFINITION_MANAGE)
            .name("流程定义管理")
            .module("workflow")
            .enabled(true)
            // @Builder 会忽略字段初始化表达式（Lombok 语义）：不显式给 roles 就是 null，
            // 下面 permission.getRoles().contains(...) 直接 NPE。与 grantAgentUseToAllRoles 同一个坑。
            .roles(new LinkedHashSet<>())
            .build()));
    if (!permission.getRoles().contains(role)) {
      permission.getRoles().add(role);
      permissionRepository.save(permission);
    }
  }

  /**
   * 把「AI 助手使用」权限点授给<b>全部现有角色</b>（ADR-M013）。幂等：已授则跳过。
   *
   * <p>为什么在代码里而不是在 V20 里：Flyway 阶段 {@code sys_role} 还是空的（角色种子由本类/演示数据写），
   * 迁移里的授权语句匹配不到任何行，与 V14 同一个坑。与流程定义权限点的差别在于授予面：
   * 那个只给管理员，可以跟在"新建管理员账号"分支里；这个要给<b>所有</b>角色，且必须覆盖
   * 已有账号的库（开发库/生产库的 {@code sys_user} 非空、走不到演示数据分支），
   * 所以每次启动都遍历角色表补授——上线当天可用性与上线前一致，此后可按角色单独回收。</p>
   */
  void grantAgentUseToAllRoles() {
    Permission permission = permissionRepository.findByCode(Permissions.AI_AGENT_USE)
        .orElseGet(() -> permissionRepository.save(Permission.builder()
            .code(Permissions.AI_AGENT_USE)
            .name("AI 助手使用")
            .module("ai")
            .enabled(true)
            // @Builder 会忽略字段初始化表达式（Lombok 语义），不显式给就是 null，
            // 下面 getRoles().add(...) 会直接 NPE。
            .roles(new LinkedHashSet<>())
            .build()));
    boolean changed = false;
    for (Role role : roleRepository.findAll()) {
      if (permission.getRoles().add(role)) {
        changed = true;
      }
    }
    if (changed) {
      permissionRepository.save(permission);
    }
  }

  private Role role(String code, String name, String dataScope) {
    return Role.builder().code(code).name(name).dataScope(dataScope).enabled(true).build();
  }

  /** 角色按 code 幂等落库：已存在即沿用；与 V10 迁移种下的 3 个新角色码口径一致（ADR-M005）。 */
  private Role ensureRole(String code, String name, String dataScope) {
    return roleRepository.findByCode(code)
        .orElseGet(() -> roleRepository.save(role(code, name, dataScope)));
  }

  private void saveUser(String username, String name, String hash, OrgUnit org, Long projectId,
      String employmentType, String phone, Role... roles) {
    User user = User.builder()
        .username(username).name(name).passwordHash(hash)
        .orgId(org.getId()).projectId(projectId)
        // 账号归属单位 = 其组织单元的归属单位（阶段 2 起 company_id 非空，ADR-M008）
        .companyId(org.getCompanyId())
        .employmentType(employmentType).phone(phone).enabled(true)
        .roles(new java.util.HashSet<>(List.of(roles)))
        .build();
    userRepository.save(user);
  }
}
