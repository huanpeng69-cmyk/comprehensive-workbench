package com.workbench.identity.repository;

import com.workbench.identity.domain.Permission;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 权限点字典仓储。 */
public interface PermissionRepository extends JpaRepository<Permission, Long> {

  /**
   * 按角色码查这些角色持有的权限点 code。
   *
   * <p>一次 join 拿完，避免调用方按角色逐个查；去重由 SQL 的 distinct 保证
   * （同一个权限点可能授给多个角色）。</p>
   */
  @Query("select distinct p.code from Permission p join p.roles r where r.code in :roleCodes")
  Set<String> findCodesByRoleCodes(@Param("roleCodes") Collection<String> roleCodes);

  /** 按权限点码查（DataInitializer 授权时用：存在则复用，不存在则补建）。 */
  Optional<Permission> findByCode(String code);
}
