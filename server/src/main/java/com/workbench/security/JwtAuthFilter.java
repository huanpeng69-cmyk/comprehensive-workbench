package com.workbench.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从 Authorization 头解析 JWT 并写入安全上下文，角色码作为权限标识。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

  private final JwtUtil jwtUtil;

  public JwtAuthFilter(JwtUtil jwtUtil) {
    this.jwtUtil = jwtUtil;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
      try {
        Claims claims = jwtUtil.parse(header.substring(7));
        String username = claims.getSubject();
        var authorities = claims.get("roles", java.util.List.class);
        var granted = authorities != null
            ? authorities.stream().map(a -> new SimpleGrantedAuthority("ROLE_" + a)).toList()
            : Collections.<SimpleGrantedAuthority>emptyList();
        var auth = new UsernamePasswordAuthenticationToken(username, null, granted);
        // 把 claims 存入 details，业务层可据此做项目部级数据过滤
        auth.setDetails(claims);
        SecurityContextHolder.getContext().setAuthentication(auth);
      } catch (Exception ignored) {
        SecurityContextHolder.clearContext();
      }
    }
    filterChain.doFilter(request, response);
  }
}
