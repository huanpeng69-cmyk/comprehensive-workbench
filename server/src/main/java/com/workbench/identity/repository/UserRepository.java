package com.workbench.identity.repository;

import com.workbench.identity.domain.User;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByUsername(String username);

  /** 登录时一次性带出角色，避免懒加载会话问题。 */
  @Query("""
      select distinct u from User u
      left join fetch u.roles
      where u.username = :username
      """)
  Optional<User> findForLogin(String username);

  /** 按角色码查启用用户（流程引擎按角色解析审批人）。 */
  @Query("""
      select u from User u
      join u.roles r
      where r.code = :code and u.enabled = true
      """)
  List<User> findEnabledByRoleCode(@Param("code") String code);

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

  boolean existsByUsername(String username);
}
