package com.workbench.testsupport;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 测试夹具：模拟 {@code JwtAuthFilter} 处理完 Bearer token 之后的安全上下文。
 *
 * <p>细节必须与 JwtAuthFilter 保持一致：authorities 形如 {@code ROLE_<角色码>}，
 * {@code Authentication.details} 是 JWT Claims（{@code CurrentUserHolder.currentProjectId()} /
 * {@code currentCompanyId()} 依赖它是 Claims 实例，否则这两个维度都取不到）。</p>
 */
public final class SecurityContextFixture {

  private SecurityContextFixture() {
  }

  /** 以指定用户身份执行后续调用；roleCodes 不带 ROLE_ 前缀，Claims 里没有 companyId。 */
  public static void runAs(String username, Long projectId, String... roleCodes) {
    runAsInCompany(username, null, projectId, roleCodes);
  }

  /**
   * 阶段 2：以「有明确归属单位」的用户身份执行后续调用。
   *
   * <p>与 {@link #runAs(String, Long, String...)} 的唯一区别是 Claims 里多一个 {@code companyId}，
   * 用来覆盖 {@code CurrentUserHolder.currentCompanyId()} 的读取路径；传入 null 即等价于
   * 阶段 2 之前签发的旧 token（Claims 里没有该 claim）。</p>
   */
  public static void runAsInCompany(String username, Long companyId, Long projectId,
      String... roleCodes) {
    List<SimpleGrantedAuthority> authorities = Arrays.stream(roleCodes)
        .map(code -> new SimpleGrantedAuthority("ROLE_" + code))
        .toList();
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(username, null, authorities);
    auth.setDetails(claims(username, companyId, projectId));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  public static void clear() {
    SecurityContextHolder.clearContext();
  }

  private static Claims claims(String username, Long companyId, Long projectId) {
    var builder = Jwts.claims().subject(username);
    if (companyId != null) {
      builder.add("companyId", companyId);
    }
    if (projectId != null) {
      builder.add("projectId", projectId);
    }
    return builder.build();
  }
}
