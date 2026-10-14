package com.workbench.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 签发与解析。密钥与有效期来自 application.yml。
 *
 * <p>阶段 2 起 token 额外携带 {@code companyId}（数据归属单位），claim 名为 {@code companyId}；
 * 值为 null 时不携带该 claim，与 {@code projectId} 的历史行为保持一致。</p>
 *
 * <p>阶段 3 起额外携带 {@code permissions}（权限点列表，如 {@code workflow:definition:manage}），
 * 供 {@code @PreAuthorize} 做后台写操作的门槛。旧 token 没有该 claim，读取方必须容忍缺失。</p>
 */
@Component
public class JwtUtil {

  @Value("${workbench.jwt.secret}")
  private String secret;

  @Value("${workbench.jwt.expiration-minutes}")
  private long expirationMinutes;

  private SecretKey key;

  @PostConstruct
  void init() {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 签发 token。
   *
   * @param companyId   数据归属单位 id（{@code org_unit.type='COMPANY'} 行），可为 null；
   *                    null 时不写入该 claim（旧 token 与该口径一致）
   * @param projectId   项目部 id，可为 null（单位级角色不带项目部）；null 时不写入该 claim
   * @param permissions 权限点列表，可为 null（旧 token 与该口径一致）
   */
  public String generate(String username, Long userId, Long companyId, Long projectId,
      java.util.List<String> roles, java.util.List<String> permissions) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + expirationMinutes * 60_000);
    JwtBuilder builder = Jwts.builder()
        .subject(username)
        .claim("userId", userId)
        .claim("roles", roles)
        .claim("permissions", permissions == null ? java.util.List.of() : permissions)
        .issuedAt(now)
        .expiration(expiry);
    // null 值必须跳过：JJWT 会把 null 序列化成显式的 "claim": null，
    // 而 CurrentUserHolder 与 SecurityContextFixture 的口径都是「没有该 claim」，
    // 两者混用会让「未携带」与「携带了 null」难以区分。
    if (companyId != null) {
      builder.claim("companyId", companyId);
    }
    if (projectId != null) {
      builder.claim("projectId", projectId);
    }
    return builder.signWith(key).compact();
  }

  public Claims parse(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
