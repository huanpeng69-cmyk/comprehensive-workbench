package com.workbench.identity.repository;

import com.workbench.identity.domain.User;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

  /**
   * 按「单位 + 登录名」查用户。阶段 2 起用户名只在单位内唯一（{@code (company_id, username)}，
   * ADR-M006），故全局单值版 {@code findByUsername} 已移除：它会把跨单位命中变成一个不确定的结果，
   * 多命中时还会抛 {@code IncorrectResultSizeDataAccessException}。
   */
  Optional<User> findByCompanyIdAndUsername(Long companyId, String username);

  /** 「单位 + 登录名」是否存在（成员创建的唯一性校验、转办目标校验）。 */
  boolean existsByCompanyIdAndUsername(Long companyId, String username);

  /**
   * 登录：按用户名取候选账号，一次性带出角色（避免懒加载会话问题）。
   *
   * <p>阶段 2 起用户名只在单位内唯一（{@code (company_id, username)}，ADR-M006），同一用户名
   * 可能命中多家单位，故返回 List 交由调用方定位。旧的单值版
   * {@code findForLogin} 在多命中时会抛 {@code IncorrectResultSizeDataAccessException}，已移除。</p>
   */
  @Query("""
      select distinct u from User u
      left join fetch u.roles
      where u.username = :username
      """)
  List<User> findAllForLoginByUsername(@Param("username") String username);

  /** 本单位内按角色码查启用用户（流程引擎按实例所属单位解析审批人）。 */
  @Query("""
      select u from User u
      join u.roles r
      where r.code = :code and u.companyId = :companyId and u.enabled = true
      """)
  List<User> findEnabledByRoleCodeAndCompanyId(@Param("code") String code,
      @Param("companyId") Long companyId);

  /** 项目部内按角色查启用用户（如项目部项目经理）。 */
  @Query("""
      select u from User u
      join u.roles r
      where r.code = :code and u.projectId = :projectId and u.enabled = true
      """)
  List<User> findEnabledByRoleCodeAndProjectId(@Param("code") String code,
      @Param("projectId") Long projectId);

  /** 批量按用户名查用户（待办与审批记录展示真实姓名）。 */
  List<User> findByUsernameIn(Collection<String> usernames);

}
