package com.workbench.party.application;

import com.workbench.audit.application.AuditService;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.party.domain.PartyFee;
import com.workbench.party.repository.PartyFeeRepository;
import com.workbench.security.CurrentUserHolder;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 党费查询服务。缴费基数关联工资，属敏感数据：所有查询仅限全公司数据范围角色，
 * PROJECT 角色调用一律拒绝并写 ACCESS_DENIED 审计（与工资模块同一策略）。
 *
 * <p>数据范围（阶段 2，ADR-M008）：全公司数据范围也只是「本单位全公司」——查询一律按
 * 当前登录单位（company_id）过滤；登录态未携带所属单位时返回空集，绝不跨单位泄露。
 */
@Service
public class PartyFeeService {

  private final PartyFeeRepository partyFeeRepository;
  private final DataScopeResolver dataScopeResolver;
  private final AuditService auditService;

  public PartyFeeService(PartyFeeRepository partyFeeRepository,
      DataScopeResolver dataScopeResolver, AuditService auditService) {
    this.partyFeeRepository = partyFeeRepository;
    this.dataScopeResolver = dataScopeResolver;
    this.auditService = auditService;
  }

  /** quarter 为空返回本单位全部季度，否则按季度过滤（按 id 升序，与花名册顺序一致）。 */
  @Transactional(readOnly = true)
  public List<PartyFee> list(String quarter) {
    assertCompanyScope("查看党费明细");
    Long companyId = dataScopeResolver.currentCompanyId();
    if (companyId == null) {
      // 有全公司数据范围但登录态无所属单位：宁可返回空集，也不猜单位
      return List.of();
    }
    if (quarter == null || quarter.isBlank()) {
      return partyFeeRepository.findByCompanyIdOrderByIdAsc(companyId);
    }
    return partyFeeRepository.findByCompanyIdAndQuarterOrderByIdAsc(companyId, quarter);
  }

  private void assertCompanyScope(String action) {
    if (dataScopeResolver.hasCompanyScope()) {
      return;
    }
    auditService.log(CurrentUserHolder.currentUsername(), "ACCESS_DENIED", "PARTY_FEE", "-",
        "非全公司数据范围用户尝试" + action + "（已拒绝）");
    throw new IllegalStateException("党费数据仅限全公司数据权限用户访问");
  }
}
