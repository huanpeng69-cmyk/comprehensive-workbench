package com.workbench.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 阶段 0 回归基线：Bearer token → 安全上下文 的映射。
 *
 * <p>锁定的事实：角色以 {@code ROLE_} 前缀进 authorities，claims 存入 details，
 * 过期/伪造 token 一律清空上下文且不抛异常（由此走到 401）。
 * 阶段 2 起 claims 还带 {@code companyId}，业务层经 {@link CurrentUserHolder} 读取。</p>
 */
class JwtAuthFilterTest {

  private final JwtUtil jwtUtil = newJwtUtil(720);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  private static JwtUtil newJwtUtil(long expirationMinutes) {
    JwtUtil util = new JwtUtil();
    ReflectionTestUtils.setField(util, "secret", "stage0-baseline-secret-long-enough-for-hmac-sha256");
    ReflectionTestUtils.setField(util, "expirationMinutes", expirationMinutes);
    util.init();
    return util;
  }

  private FilterChain filter(String authorizationHeader) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/hr/employees");
    if (authorizationHeader != null) {
      request.addHeader("Authorization", authorizationHeader);
    }
    FilterChain chain = mock(FilterChain.class);
    new JwtAuthFilter(jwtUtil).doFilter(request, new MockHttpServletResponse(), chain);
    return chain;
  }

  /** 断言过滤器把请求交给了下游链；漏掉就会全站 HTTP 200 空 body（2026-10-14 事故）。 */
  private static void verifyProceeded(FilterChain chain) throws Exception {
    verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void validBearerTokenEstablishesAuthenticatedContext() throws Exception {
    String token = jwtUtil.generate("chenguodong", 7L, 9L, 2L, List.of("PROJECT_MANAGER", "HR_ADMIN"), List.of());

    FilterChain chain = filter("Bearer " + token);

    var auth = SecurityContextHolder.getContext().getAuthentication();
    assertNotNull(auth);
    assertEquals("chenguodong", auth.getName());
    assertEquals(List.of("ROLE_PROJECT_MANAGER", "ROLE_HR_ADMIN"),
        auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
    assertTrue(auth.getDetails() instanceof Claims, "details 必须是 Claims，否则取不到 projectId");
    verifyProceeded(chain);
  }

  @Test
  void businessLayerReadsProjectAndRolesFromFilterContext() throws Exception {
    String token = jwtUtil.generate("wanghaitao", 3L, 4L, 5L, List.of("EMPLOYEE"), List.of());

    filter("Bearer " + token);

    assertEquals("wanghaitao", CurrentUserHolder.currentUsername());
    assertEquals(4L, CurrentUserHolder.currentCompanyId());
    assertEquals(5L, CurrentUserHolder.currentProjectId());
    assertEquals(List.of("EMPLOYEE"), CurrentUserHolder.currentRoleCodes());
  }

  @Test
  void missingAuthorizationHeaderLeavesContextAnonymous() throws Exception {
    FilterChain chain = filter(null);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    // 无 token / 过期 / 非法 token 也必须继续放行，由下游 AuthorizationFilter 决定 401/403。
    verifyProceeded(chain);
  }

  @Test
  void expiredTokenLeavesContextAnonymous() throws Exception {
    String token = newJwtUtil(-1).generate("chenguodong", 7L, 9L, 2L, List.of("EMPLOYEE"), List.of());

    FilterChain chain = filter("Bearer " + token);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verifyProceeded(chain);
  }

  @Test
  void malformedTokenLeavesContextAnonymous() throws Exception {
    FilterChain chain = filter("Bearer not-a-jwt");

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verifyProceeded(chain);
  }

  @Test
  void nonBearerSchemeIsIgnored() throws Exception {
    String token = jwtUtil.generate("chenguodong", 7L, 9L, 2L, List.of("EMPLOYEE"), List.of());

    FilterChain chain = filter("Basic " + token);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verifyProceeded(chain);
  }

  @Test
  void permissionCodesBecomeAuthoritiesWithoutPrefix() throws Exception {
    // 阶段 3（ADR-M010）：@PreAuthorize("hasAuthority('workflow:definition:manage')") 按权限点码
    // 原样匹配，因此 Filter 必须把权限点码原样放进 authorities，不能加 ROLE_ 前缀。
    String token = jwtUtil.generate("admin", 7L, 1L, 1L, List.of("SYSTEM_ADMIN"),
        List.of("workflow:definition:manage"));

    filter("Bearer " + token);

    var auth = SecurityContextHolder.getContext().getAuthentication();
    assertNotNull(auth);
    assertEquals(List.of("ROLE_SYSTEM_ADMIN", "workflow:definition:manage"),
        auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
  }

  @Test
  void legacyTokenWithoutPermissionsClaimStillAuthenticates() throws Exception {
    // 旧 token 没有 permissions claim：必须容忍缺失，只按角色建上下文，
    // 否则升级后所有未重新登录的会话会被打成匿名（大面积 401）。
    String token = jwtUtil.generate("chenguodong", 7L, 9L, 2L, List.of("PROJECT_MANAGER"), null);

    filter("Bearer " + token);

    var auth = SecurityContextHolder.getContext().getAuthentication();
    assertNotNull(auth);
    assertEquals(List.of("ROLE_PROJECT_MANAGER"),
        auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
  }
}
