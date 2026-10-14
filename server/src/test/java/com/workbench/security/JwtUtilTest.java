package com.workbench.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 阶段 0 回归基线：JWT 签发/解析契约。
 *
 * <p>锁定的事实：claim 名为 {@code userId} / {@code companyId} / {@code projectId} / {@code roles}，
 * 角色以角色码列表存放（不带 ROLE_ 前缀），projectId 可为 null。
 * 阶段 2 已按此处约定扩展 {@code companyId}（新增 claim 维度，既有 claim 语义逐字未改）。</p>
 */
class JwtUtilTest {

  private static final String SECRET = "stage0-baseline-secret-long-enough-for-hmac-sha256-signing";

  private static JwtUtil util(String secret, long expirationMinutes) {
    JwtUtil jwtUtil = new JwtUtil();
    ReflectionTestUtils.setField(jwtUtil, "secret", secret);
    ReflectionTestUtils.setField(jwtUtil, "expirationMinutes", expirationMinutes);
    jwtUtil.init();
    return jwtUtil;
  }

  @Test
  void generatedTokenCarriesUserCompanyProjectAndRoles() {
    String token =
        util(SECRET, 720).generate("chenguodong", 7L, 9L, 2L, List.of("PROJECT_MANAGER"), List.of());

    Claims claims = util(SECRET, 720).parse(token);

    assertEquals("chenguodong", claims.getSubject());
    assertEquals(7, ((Number) claims.get("userId")).intValue());
    assertEquals(9, ((Number) claims.get("companyId")).intValue());
    assertEquals(2, ((Number) claims.get("projectId")).intValue());
    assertEquals(List.of("PROJECT_MANAGER"), claims.get("roles", List.class));
  }

  @Test
  void expirationFollowsConfiguredMinutes() {
    String token = util(SECRET, 720).generate("chenguodong", 7L, 9L, 2L, List.of("EMPLOYEE"), List.of());

    Claims claims = util(SECRET, 720).parse(token);

    long ttlMinutes = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 60_000;
    assertEquals(720L, ttlMinutes);
  }

  @Test
  void projectIdIsAbsentForUsersWithoutProject() {
    String token = util(SECRET, 720).generate("admin", 1L, null, null, List.of("SYSTEM_ADMIN"), List.of());

    Claims claims = util(SECRET, 720).parse(token);
    assertNull(claims.get("projectId"));
    assertNull(claims.get("companyId"));
  }

  @Test
  void companyIdIsAbsentWhenNullButPresentWhenGiven() {
    String token =
        util(SECRET, 720).generate("union_hd", 9L, null, null, List.of("UNION_LEADER"), List.of());

    Claims claims = util(SECRET, 720).parse(token);
    assertNull(claims.get("companyId"));
    assertNull(claims.get("projectId"));
    assertEquals("union_hd", claims.getSubject());
    assertEquals(List.of("UNION_LEADER"), claims.get("roles", List.class));

  }

  @Test
  void permissionsClaimCarriesGrantedPermissionCodes() {
    String token = util(SECRET, 720).generate("admin", 7L, 1L, 1L,
        List.of("SYSTEM_ADMIN"), List.of("workflow:definition:manage"));

    Claims claims = util(SECRET, 720).parse(token);
    assertEquals(List.of("workflow:definition:manage"), claims.get("permissions", List.class));
  }

  @Test
  void permissionsClaimIsEmptyListWhenNoneGranted() {
    // 空集合而不是 null：@PreAuthorize 读 claim 时期望拿到可遍历的列表，
    // null 会让 Filter 里多做一次判空，且与「没有该 claim」难以区分。
    String token = util(SECRET, 720).generate("zhangsan", 8L, 1L, 2L,
        List.of("EMPLOYEE"), List.of());

    Claims claims = util(SECRET, 720).parse(token);
    assertEquals(List.of(), claims.get("permissions", List.class));
  }
  @Test
  void companyIdAndProjectIdAreCarriedWhenGiven() {
    String token =
        util(SECRET, 720).generate("fangjing", 3L, 1L, 2L, List.of("FINANCE", "UNION_LEADER"), List.of());

    Claims claims = util(SECRET, 720).parse(token);
    assertEquals(1, ((Number) claims.get("companyId")).intValue());
    assertEquals(2, ((Number) claims.get("projectId")).intValue());
  }


  @Test
  void companyIdPresentWithoutProjectId() {
    String token = util(SECRET, 720).generate("boss", 10L, 14L, null, List.of("UNION_LEADER"), List.of());

    Claims claims = util(SECRET, 720).parse(token);
    assertEquals(14, ((Number) claims.get("companyId")).intValue());
    assertNull(claims.get("projectId"));
  }

  @Test
  void expiredTokenIsRejectedAsExpiredJwtException() {
    String token = util(SECRET, -1).generate("chenguodong", 7L, 9L, 2L, List.of("EMPLOYEE"), List.of());

    assertThrows(ExpiredJwtException.class, () -> util(SECRET, 720).parse(token));
  }

  @Test
  void tokenSignedWithAnotherSecretIsRejected() {
    String token = util("another-secret-also-long-enough-for-hmac-sha256-signing-x", 720)
        .generate("chenguodong", 7L, 9L, 2L, List.of("EMPLOYEE"), List.of());

    assertThrows(JwtException.class, () -> util(SECRET, 720).parse(token));
  }

  @Test
  void tamperedTokenIsRejected() {
    String token = util(SECRET, 720).generate("chenguodong", 7L, 9L, 2L, List.of("EMPLOYEE"), List.of());
    char last = token.charAt(token.length() - 1);
    String tampered = token.substring(0, token.length() - 1) + (last == 'a' ? 'b' : 'a');

    assertThrows(JwtException.class, () -> util(SECRET, 720).parse(tampered));
  }
}
