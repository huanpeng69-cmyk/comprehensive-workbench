package com.workbench.platform.identity;

import java.util.Set;

/**
 * 权限点查询端口：按角色码查这些角色持有的权限点（阶段 3，ADR-M010）。
 *
 * <p>业务侧（如登录签发 JWT）只拿得到权限点字符串，接触不到 {@code sys_permission} 实体，
 * 因此无法自己拼一套权限判断逻辑。</p>
 */
public interface PermissionQueryPort {

  /**
   * 查这些角色码名下的全部权限点 code。
   *
   * @param roleCodes 角色码；null 或空时返回空集合，不发起查询
   * @return 权限点 code 集合；不承诺可变
   */
  Set<String> findByRoleCodes(java.util.Collection<String> roleCodes);
}
