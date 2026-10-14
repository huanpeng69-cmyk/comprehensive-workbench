package com.workbench.security;

import io.jsonwebtoken.Claims;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从安全上下文取当前登录用户信息，供业务层做「单位 → 项目部」两级数据过滤。
 */
public final class CurrentUserHolder {

  private CurrentUserHolder() {
  }

  public static String currentUsername() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getName() != null ? auth.getName() : null;
  }

  /**
   * JWT 里带的项目部 id，null 表示未指定。COMPANY 级角色可为 null，表示不做项目部过滤。
   */
  public static Long currentProjectId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getDetails() instanceof Claims claims)) {
      return null;
    }
    Object value = claims.get("projectId");
    return value instanceof Number num ? num.longValue() : null;
  }

  /**
   * JWT 里带的数据归属单位 id（{@code org_unit.type='COMPANY'} 行）。
   *
   * <p>null 表示未指定：阶段 2 之前签发的旧 token 和不带单位的系统 token 都会是 null。
   * 写入路径必须把它当作「无法确定数据归属」处理，不得默认落到任意一家公司。</p>
   */
  public static Long currentCompanyId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getDetails() instanceof Claims claims)) {
      return null;
    }
    Object value = claims.get("companyId");
    return value instanceof Number num ? num.longValue() : null;
  }

  /**
   * 当前用户的角色码列表（去掉 Spring Security 的 ROLE_ 前缀）。
   */
  public static java.util.List<String> currentRoleCodes() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null) {
      return java.util.Collections.emptyList();
    }
    return auth.getAuthorities().stream()
        .map(a -> a.getAuthority())
        .filter(r -> r != null && r.startsWith("ROLE_"))
        .map(r -> r.substring("ROLE_".length()))
        .toList();
  }
}
