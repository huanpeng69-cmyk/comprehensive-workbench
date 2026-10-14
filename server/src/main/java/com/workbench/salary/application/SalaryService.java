package com.workbench.salary.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbench.audit.application.AuditService;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.platform.org.WriteOwnershipResolver;
import com.workbench.salary.application.dto.MonthUpsertRequest;
import com.workbench.salary.application.dto.MonthVo;
import com.workbench.salary.domain.PerfRecord;
import com.workbench.salary.domain.SalaryConfig;
import com.workbench.salary.domain.SalaryMonth;
import com.workbench.salary.domain.SalaryRecord;
import com.workbench.salary.repository.PerfRecordRepository;
import com.workbench.salary.repository.SalaryConfigRepository;
import com.workbench.salary.repository.SalaryMonthRepository;
import com.workbench.salary.repository.SalaryRecordRepository;
import com.workbench.security.CurrentUserHolder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工资制作服务。整个模块为敏感数据：所有读写仅限 COMPANY 数据范围角色，
 * PROJECT 角色调用（含查询）一律拒绝并写 ACCESS_DENIED 审计。
 * 审计 detail 不含工资数额、身份证等敏感值。
 *
 * <p>数据范围（阶段 2，ADR-M008）：全公司数据范围也只是「本单位全公司」——月表、工资明细、
 * 绩效明细一律按当前登录单位（company_id）过滤；登录态缺失所属单位时查询返回空集、
 * 写入经 WriteOwnershipResolver 拒绝并审计，绝不跨单位读写。
 */
@Service
public class SalaryService {

  private final SalaryMonthRepository monthRepository;
  private final SalaryRecordRepository recordRepository;
  private final PerfRecordRepository perfRepository;
  private final SalaryConfigRepository configRepository;
  private final DataScopeResolver dataScopeResolver;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;
  private final WriteOwnershipResolver writeOwnershipResolver;

  public SalaryService(SalaryMonthRepository monthRepository,
      SalaryRecordRepository recordRepository, PerfRecordRepository perfRepository,
      SalaryConfigRepository configRepository, DataScopeResolver dataScopeResolver,
      AuditService auditService, ObjectMapper objectMapper,
      WriteOwnershipResolver writeOwnershipResolver) {
    this.monthRepository = monthRepository;
    this.recordRepository = recordRepository;
    this.perfRepository = perfRepository;
    this.configRepository = configRepository;
    this.dataScopeResolver = dataScopeResolver;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
    this.writeOwnershipResolver = writeOwnershipResolver;
  }

  /** 工资模块总闸门：所有接口（含查询）都需要全公司数据范围。 */
  private void assertCompanyScope(String action) {
    if (dataScopeResolver.hasCompanyScope()) {
      return;
    }
    auditService.log(CurrentUserHolder.currentUsername(), "ACCESS_DENIED", "SALARY", "-",
        "非全公司数据范围用户尝试" + action + "（已拒绝）");
    throw new IllegalStateException("工资数据仅限全公司数据权限用户访问");
  }

  @Transactional(readOnly = true)
  public List<MonthVo> listMonths() {
    assertCompanyScope("查看工资数据");
    Long companyId = dataScopeResolver.currentCompanyId();
    if (companyId == null) {
      // 有全公司数据范围但登录态无所属单位：宁可返回空集，也不猜单位
      return List.of();
    }
    return monthRepository.findByCompanyIdOrderByMonthDesc(companyId).stream()
        .map(this::toVo)
        .toList();
  }

  /** 整月覆盖式保存（Excel 导入）：覆盖范围限本单位，绝不触碰其他单位的同月数据。 */
  @Transactional
  public MonthVo upsertMonth(String month, MonthUpsertRequest request) {
    Long companyId = writeOwnershipResolver.requireCompanyId(null, "SALARY", "保存工资月度数据");
    SalaryMonth header = monthRepository.findByCompanyIdAndMonth(companyId, month).orElse(null);
    if (header == null) {
      header = monthRepository.save(SalaryMonth.builder().month(month).companyId(companyId)
          .importedAt(LocalDateTime.now()).build());
    } else {
      header.setImportedAt(LocalDateTime.now());
      monthRepository.save(header);
    }
    List<SalaryRecord> records = request.records() == null ? List.of() : request.records();
    List<PerfRecord> perfs = request.perf() == null ? List.of() : request.perf();
    // 整月替换（限本单位）：先删后插，保证与前端数据一致
    recordRepository.deleteAll(
        recordRepository.findByCompanyIdAndMonthOrderBySeqAscIdAsc(companyId, month));
    perfRepository.deleteAll(
        perfRepository.findByCompanyIdAndMonthOrderBySeqAscIdAsc(companyId, month));
    for (SalaryRecord r : records) {
      r.setMonth(month);
      r.setCompanyId(companyId);
      if (r.getId() == null || r.getId().isBlank()) {
        r.setId(month + "-" + r.getName());
      }
    }
    for (PerfRecord p : perfs) {
      p.setMonth(month);
      p.setCompanyId(companyId);
      if (p.getId() == null || p.getId().isBlank()) {
        p.setId(month + "-" + p.getName());
      }
    }
    recordRepository.saveAll(records);
    perfRepository.saveAll(perfs);
    auditService.log("SALARY_IMPORT", "SALARY", month,
        "保存工资月度数据 " + month + "（工资 " + records.size() + " 条 / 绩效 " + perfs.size() + " 条）");
    return toVo(header);
  }

  @Transactional
  public void deleteMonth(String month) {
    Long companyId = writeOwnershipResolver.requireCompanyId(null, "SALARY", "删除工资月度数据");
    recordRepository.deleteAll(
        recordRepository.findByCompanyIdAndMonthOrderBySeqAscIdAsc(companyId, month));
    perfRepository.deleteAll(
        perfRepository.findByCompanyIdAndMonthOrderBySeqAscIdAsc(companyId, month));
    monthRepository.findByCompanyIdAndMonth(companyId, month).ifPresent(monthRepository::delete);
    auditService.log("SALARY_MONTH_DELETE", "SALARY", month, "删除工资月度数据 " + month);
  }

  @Transactional
  public SalaryRecord updateRecord(String month, String recordId, SalaryRecord incoming) {
    Long companyId = writeOwnershipResolver.requireCompanyId(null, "SALARY", "修改工资明细");
    SalaryRecord existing =
        recordRepository.findByCompanyIdAndMonthAndId(companyId, month, recordId)
            .orElseThrow(() -> new IllegalArgumentException("工资记录不存在：" + recordId));
    copyEditable(existing, incoming);
    existing.setMonth(month);
    existing.setId(recordId);
    SalaryRecord saved = recordRepository.save(existing);
    auditService.log("SALARY_UPDATE", "SALARY", month + "/" + recordId,
        "修改工资明细（员工：" + saved.getName() + "）");
    return saved;
  }

  /** 新增单条工资明细（对齐花名册「新增人员」）。id 约定为 `${month}-${name}`，同单位同月同名已存在则拒绝。 */
  @Transactional
  public SalaryRecord createRecord(String month, SalaryRecord incoming) {
    Long companyId = writeOwnershipResolver.requireCompanyId(null, "SALARY", "新增工资明细");
    if (incoming.getName() == null || incoming.getName().isBlank()) {
      throw new IllegalArgumentException("姓名不能为空");
    }
    String id = month + "-" + incoming.getName();
    if (recordRepository.findByCompanyIdAndMonthAndId(companyId, month, id).isPresent()) {
      throw new IllegalStateException("员工「" + incoming.getName() + "」" + month + " 工资记录已存在，请直接编辑");
    }
    monthRepository.findByCompanyIdAndMonth(companyId, month).orElseGet(() ->
        monthRepository.save(SalaryMonth.builder().month(month).companyId(companyId)
            .importedAt(LocalDateTime.now()).build()));
    if (incoming.getSeq() == null) {
      incoming.setSeq(recordRepository
          .findByCompanyIdAndMonthOrderBySeqAscIdAsc(companyId, month).size() + 1);
    }
    incoming.setMonth(month);
    incoming.setId(id);
    incoming.setCompanyId(companyId);
    SalaryRecord saved = recordRepository.save(incoming);
    auditService.log("SALARY_CREATE", "SALARY", month + "/" + id,
        "新增工资明细（员工：" + saved.getName() + "）");
    return saved;
  }

  /** 删除单条工资明细（对齐花名册「删除此行」）。 */
  @Transactional
  public void deleteRecord(String month, String recordId) {
    Long companyId = writeOwnershipResolver.requireCompanyId(null, "SALARY", "删除工资明细");
    SalaryRecord existing =
        recordRepository.findByCompanyIdAndMonthAndId(companyId, month, recordId)
            .orElseThrow(() -> new IllegalArgumentException("工资记录不存在：" + recordId));
    recordRepository.delete(existing);
    auditService.log("SALARY_RECORD_DELETE", "SALARY", month + "/" + recordId,
        "删除工资明细（员工：" + existing.getName() + "）");
  }

  @Transactional
  public PerfRecord updatePerf(String month, String recordId, PerfRecord incoming) {
    Long companyId = writeOwnershipResolver.requireCompanyId(null, "SALARY", "修改绩效记录");
    PerfRecord existing = perfRepository.findByCompanyIdAndMonthAndId(companyId, month, recordId)
        .orElseThrow(() -> new IllegalArgumentException("绩效记录不存在：" + recordId));
    copyPerf(existing, incoming);
    existing.setMonth(month);
    existing.setId(recordId);
    PerfRecord saved = perfRepository.save(existing);
    auditService.log("SALARY_PERF_UPDATE", "SALARY", month + "/" + recordId,
        "修改绩效记录（员工：" + saved.getName() + "）");
    return saved;
  }

  /** 新增单条绩效明细；同单位同月同名仅允许保留一条记录。 */
  @Transactional
  public PerfRecord createPerf(String month, PerfRecord incoming) {
    Long companyId = writeOwnershipResolver.requireCompanyId(null, "SALARY", "新增绩效记录");
    if (incoming.getName() == null || incoming.getName().isBlank()) {
      throw new IllegalArgumentException("姓名不能为空");
    }
    String id = month + "-" + incoming.getName().trim();
    if (perfRepository.findByCompanyIdAndMonthAndId(companyId, month, id).isPresent()) {
      throw new IllegalStateException("员工「" + incoming.getName() + "」" + month + " 绩效记录已存在，请直接编辑");
    }
    monthRepository.findByCompanyIdAndMonth(companyId, month).orElseGet(() ->
        monthRepository.save(SalaryMonth.builder().month(month).companyId(companyId)
            .importedAt(LocalDateTime.now()).build()));
    incoming.setMonth(month);
    incoming.setId(id);
    incoming.setCompanyId(companyId);
    if (incoming.getSeq() == null) {
      incoming.setSeq(perfRepository
          .findByCompanyIdAndMonthOrderBySeqAscIdAsc(companyId, month).size() + 1);
    }
    PerfRecord saved = perfRepository.save(incoming);
    auditService.log("SALARY_PERF_CREATE", "SALARY", month + "/" + id,
        "新增绩效记录（员工：" + saved.getName() + "）");
    return saved;
  }

  /** 删除单条绩效记录。 */
  @Transactional
  public void deletePerf(String month, String recordId) {
    Long companyId = writeOwnershipResolver.requireCompanyId(null, "SALARY", "删除绩效记录");
    PerfRecord existing = perfRepository.findByCompanyIdAndMonthAndId(companyId, month, recordId)
        .orElseThrow(() -> new IllegalArgumentException("绩效记录不存在：" + recordId));
    perfRepository.delete(existing);
    auditService.log("SALARY_PERF_DELETE", "SALARY", month + "/" + recordId,
        "删除绩效记录（员工：" + existing.getName() + "）");
  }

  @Transactional(readOnly = true)
  public Object getConfig() {
    assertCompanyScope("查看工资核算配置");
    return configRepository.findById(1)
        .map(c -> parse(c.getData()))
        .orElseGet(() -> Map.of());
  }

  @Transactional
  public Object saveConfig(Object data) {
    assertCompanyScope("修改工资核算配置");
    String json;
    try {
      json = objectMapper.writeValueAsString(data);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("配置格式错误");
    }
    SalaryConfig config = configRepository.findById(1).orElseGet(
        () -> SalaryConfig.builder().id(1).build());
    config.setData(json);
    configRepository.save(config);
    auditService.log("SALARY_CONFIG_UPDATE", "SALARY", "config", "修改工资核算配置（薪档标准/出工日基准）");
    return parse(json);
  }

  private Object parse(String json) {
    try {
      return objectMapper.readValue(json, Object.class);
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  private MonthVo toVo(SalaryMonth header) {
    return new MonthVo(header.getMonth(), header.getImportedAt(),
        recordRepository.findByCompanyIdAndMonthOrderBySeqAscIdAsc(header.getCompanyId(),
            header.getMonth()),
        perfRepository.findByCompanyIdAndMonthOrderBySeqAscIdAsc(header.getCompanyId(),
            header.getMonth()));
  }

  private void copyEditable(SalaryRecord t, SalaryRecord s) {
    t.setDepartment(s.getDepartment());
    t.setSeq(s.getSeq());
    t.setName(s.getName());
    t.setSalaryGrade(s.getSalaryGrade());
    t.setPostDays(s.getPostDays());
    t.setPostSalary(s.getPostSalary());
    t.setTitleAllowance(s.getTitleAllowance());
    t.setOtDays(s.getOtDays());
    t.setOvertime(s.getOvertime());
    t.setBonus(s.getBonus());
    t.setMakeUp(s.getMakeUp());
    t.setSeniority(s.getSeniority());
    t.setSiteAllowance(s.getSiteAllowance());
    t.setDueTotal(s.getDueTotal());
    t.setPension(s.getPension());
    t.setUnemployment(s.getUnemployment());
    t.setMedical(s.getMedical());
    t.setHousingFund(s.getHousingFund());
    t.setAnnuity(s.getAnnuity());
    t.setLargeMedical(s.getLargeMedical());
    t.setOtherDeduct(s.getOtherDeduct());
    t.setChildEdu(s.getChildEdu());
    t.setContinuingEdu(s.getContinuingEdu());
    t.setHousingLoan(s.getHousingLoan());
    t.setHousingRent(s.getHousingRent());
    t.setElderlyCare(s.getElderlyCare());
    t.setInfantCare(s.getInfantCare());
    t.setTax(s.getTax());
    t.setTaxAdjust(s.getTaxAdjust());
    t.setDeduct(s.getDeduct());
    t.setNetTotal(s.getNetTotal());
    t.setMonthPerf(s.getMonthPerf());
    t.setUnionFee(s.getUnionFee());
    t.setSign(s.getSign());
    t.setImportedDue(s.getImportedDue());
    t.setImportedNet(s.getImportedNet());
  }

  private void copyPerf(PerfRecord t, PerfRecord s) {
    t.setSeq(s.getSeq());
    t.setDepartment(s.getDepartment());
    t.setName(s.getName());
    t.setMonthScore(s.getMonthScore());
    t.setPerfLevel(s.getPerfLevel());
    t.setIncentiveCoef(s.getIncentiveCoef());
    t.setSepPerf(s.getSepPerf());
    t.setAugBase(s.getAugBase());
    t.setIncentivePart(s.getIncentivePart());
    t.setBalanceCoef(s.getBalanceCoef());
    t.setCoopCoef(s.getCoopCoef());
    t.setFinalIncentive(s.getFinalIncentive());
    t.setTotalPerf(s.getTotalPerf());
  }
}
