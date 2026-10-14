package com.workbench.platform.datascope;

import com.workbench.platform.org.RoleQueryPort;
import com.workbench.platform.org.RoleRef;
import com.workbench.security.CurrentUserHolder;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 基于「当前用户角色 + sys_role.data_scope」的数据范围实现。
 *
 * <p><b>逐字复刻阶段 1 之前各业务服务里的判定逻辑</b>（{@code callerHasCompanyScope} /
 * {@code assertCompanyScope}），回归基线 {@code *ServiceDataScopeTest} 锁住以下四条：
 * <ol>
 *   <li>角色码为空 → 直接 false，<b>且不查角色表</b>（避免无角色用户触发一次无谓查询）；</li>
 *   <li>只统计 {@code enabled == TRUE} 的角色，停用的 COMPANY 角色不放行；</li>
 *   <li>{@code dataScope} 是字符串比较 {@code "COMPANY"}（不做大小写归一，与旧代码一致）；</li>
 *   <li>{@code canAccessProject}：COMPANY 全放行；否则要求 projectId 非空且与记录项目部相等
 *       （含记录侧为 null 时拒绝）；</li>
 *   <li>阶段 2 新增 {@code canAccessCompany}：当前用户 companyId 非空且与记录相等才放行，
 *       用于给 COMPANY 档加单位边界（ADR-M008）。</li>
 * </ol>
 *
 * <p>{@code currentCompanyId()} / {@code canAccessCompany} 是纯委托（直接读 JWT claim，不查角色表），
 * 不参与 {@code hasCompanyScope} / {@code canAccessProject} 的判定，故阶段 2 未改动上面四条语义。</p>
 *
 * <p><b>改这里的防再犯检查</b>：任何一条语义调整都必须同步跑
 * {@code RoleBasedDataScopeResolverTest} 与 5 个 {@code *ServiceDataScopeTest}，
 * 它们会在语义被改动时变红。</p>
 */
@Component
public class RoleBasedDataScopeResolver implements DataScopeResolver {

  private static final String COMPANY_SCOPE = "COMPANY";

  private final RoleQueryPort roleQueryPort;

  public RoleBasedDataScopeResolver(RoleQueryPort roleQueryPort) {
    this.roleQueryPort = roleQueryPort;
  }

  @Override
  public boolean hasCompanyScope() {
    List<String> roleCodes = CurrentUserHolder.currentRoleCodes();
    if (roleCodes.isEmpty()) {
      return false;
    }
    return roleQueryPort.findByCodes(roleCodes).stream()
        .filter(RoleRef::enabled)
        .anyMatch(role -> COMPANY_SCOPE.equals(role.dataScope()));
  }

  @Override
  public Long currentProjectId() {
    return CurrentUserHolder.currentProjectId();
  }

  @Override
  public Long currentCompanyId() {
    return CurrentUserHolder.currentCompanyId();
  }

  @Override
  public boolean canAccessProject(Long recordProjectId) {
    if (hasCompanyScope()) {
      return true;
    }
    Long projectId = currentProjectId();
    return projectId != null && projectId.equals(recordProjectId);
  }

  @Override
  public boolean canAccessCompany(Long recordCompanyId) {
    Long companyId = currentCompanyId();
    return companyId != null && companyId.equals(recordCompanyId);
  }
}
