package com.workbench.security;

import io.jsonwebtoken.Claims;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从安全上下文取当前登录用户信息，供业务层做项目部级数据过滤。
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
}
