package com.workbench.platform.org;

import com.workbench.audit.application.AuditService;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.security.CurrentUserHolder;
import org.springframework.stereotype.Component;

/**
 * 写路径归属单位解析（阶段 2，ADR-M008）：所有新增业务数据都必须落 {@code company_id}，
 * 解析规则收在这一个地方，业务模块不得各写一份。
 *
 * <p>解析顺序：
 * <ol>
 *   <li>{@code projectId} 非空 → 上溯该项目部组织单元的所属单位；</li>
 *   <li>否则取当前登录用户的 {@code companyId}（JWT claim）；</li>
 *   <li>两者都缺失 → 写 {@code ACCESS_DENIED} 审计并抛 {@link IllegalStateException}，
 *       <b>绝不退化成不写 {@code company_id} 或落到任意一家公司</b>。</li>
 * </ol>
 *
 * <p>调用方仍须自行做「单位 + 数据范围」守卫（例如 HR 花名册写入前要求全公司数据范围、
 * 按 id 取到的记录要先过 {@link DataScopeResolver#canAccessCompany(Long)}），本类只负责
 * 「这条新数据属于哪个单位」。</p>
 */
@Component
public class WriteOwnershipResolver {

  private final OrgQueryPort orgQueryPort;
  private final DataScopeResolver dataScopeResolver;
  private final AuditService auditService;

  public WriteOwnershipResolver(OrgQueryPort orgQueryPort, DataScopeResolver dataScopeResolver,
      AuditService auditService) {
    this.orgQueryPort = orgQueryPort;
    this.dataScopeResolver = dataScopeResolver;
    this.auditService = auditService;
  }

  /**
   * 解析本次写入应落的归属单位 id；无法确定则审计并抛异常。
   *
   * @param projectId  新数据所属项目部 id，可为 null
   * @param targetType 审计 targetType（沿用各模块既有口径，如 EMPLOYEE / LEAVE / PROCESS_INSTANCE）
   * @param action     审计文案里的动作描述，如「提交请假」
   */
  public Long requireCompanyId(Long projectId, String targetType, String action) {
    if (projectId != null) {
      Long byProject = orgQueryPort.findById(projectId).map(OrgUnitRef::companyId).orElse(null);
      if (byProject != null) {
        return byProject;
      }
    }
    Long companyId = dataScopeResolver.currentCompanyId();
    if (companyId != null) {
      return companyId;
    }
    auditService.log(CurrentUserHolder.currentUsername(), "ACCESS_DENIED", targetType, "-",
        "无法确定数据归属单位，已拒绝写入：" + action);
    throw new IllegalStateException("无法确定数据归属单位，已拒绝写入");
  }
}
