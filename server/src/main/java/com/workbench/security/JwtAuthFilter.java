package com.workbench.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

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
        var roles = claims.get("roles", java.util.List.class);
        var perms = claims.get("permissions", java.util.List.class);
        var granted = new java.util.ArrayList<SimpleGrantedAuthority>();
        if (roles != null) {
          roles.forEach(r -> granted.add(new SimpleGrantedAuthority("ROLE_" + r)));
        }
        // 权限点原样作为 authority（不加前缀）：@PreAuthorize("hasAuthority('workflow:...')")
        // 直接按权限点码匹配。旧 token 没有该 claim，perms 为 null 时跳过即可。
        if (perms != null) {
          perms.forEach(p -> granted.add(new SimpleGrantedAuthority(String.valueOf(p))));
        }
        var auth = new UsernamePasswordAuthenticationToken(username, null, granted);
        // 把 claims 存入 details，业务层可据此做项目部级数据过滤
        auth.setDetails(claims);
        SecurityContextHolder.getContext().setAuthentication(auth);
      } catch (ExpiredJwtException ex) {
        // 过期：常见于长会话用户，降为 debug 避免刷日志；上下文清空后走 401，前端据此跳登录
        SecurityContextHolder.clearContext();
        log.debug("JWT expired for request {} {}: {}", request.getMethod(), request.getRequestURI(),
            ex.getMessage());
      } catch (JwtException | IllegalArgumentException ex) {
        // 非法 token（签名不符/格式错）：与过期分开记录，便于发现伪造或异常流量
        SecurityContextHolder.clearContext();
        log.warn("Rejected invalid JWT for request {} {}: {}", request.getMethod(),
            request.getRequestURI(), ex.getClass().getSimpleName());
      }
    }
    filterChain.doFilter(request, response);
  }

  /**
   * 异步（ASYNC）分派时跳过本过滤器。
   *
   * <p>返回值与 {@link OncePerRequestFilter} 基类默认一致（同为 {@code true}），因此这是一个
   * 显式化的 no-op，行为上等价于不重写本方法。保留它是为了让「异步分派为何仍然能通过鉴权」
   * 有明确定论，避免后人误以为需要改成 {@code false}。原因如下：
   * <p>SSE 端点（{@code /api/ai/writing/agent}）返回 {@code SseEmitter} 后，Tomcat 会用另一条线程做
   * 异步分派。此时鉴权不依赖本过滤器重跑：Spring Security 默认过滤器链已注册
   * {@code WebAsyncManagerIntegrationFilter}（见 spring-security-config 的
   * {@code FilterOrderRegistration}），由它把发起请求时的 SecurityContext 传播给异步线程。
   */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return true;
  }
}
