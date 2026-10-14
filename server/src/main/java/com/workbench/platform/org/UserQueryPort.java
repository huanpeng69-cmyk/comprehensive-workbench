package com.workbench.platform.org;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户查询端口：平台内核向业务模块暴露的只读接口。
 *
 * <p>只返回 {@link UserRef} 或登录名/姓名，不外泄 identity 实体。实现见
 * {@code com.workbench.platform.identity.IdentityUserQueryAdapter}。</p>
 */
public interface UserQueryPort {

  /**
   * 按「单位 + 登录名」查用户。companyId 为 null 时返回 empty：调用方尚未确定单位时
   * 不得跨单位猜测（阶段 2 起用户名只在单位内唯一，ADR-M006）。
   */
  Optional<UserRef> findByCompanyIdAndUsername(Long companyId, String username);

  /** 「单位 + 登录名」是否存在（转办时校验目标用户在同一单位内）。 */
  boolean existsByCompanyIdAndUsername(Long companyId, String username);

  /** 指定单位内按角色码查启用用户的登录名（流程引擎按实例所属单位解析审批人）。 */
  List<String> findEnabledUsernamesByRoleCodeAndCompanyId(String roleCode, Long companyId);

  /** 指定项目部内按角色码查启用用户的登录名（如项目部项目经理）。 */
  List<String> findEnabledUsernamesByRoleCodeAndProjectId(String roleCode, Long projectId);

  /**
   * 批量查「登录名 → 姓名」，供待办/审批记录展示真实姓名。
   *
   * <p>入参为 null 或空时返回空 Map；返回的 Map <b>不承诺可变</b>，调用方需要修改时必须自行拷贝。</p>
   */
  Map<String, String> findNamesByUsernames(Collection<String> usernames);
}
