package com.workbench.security;

import io.jsonwebtoken.Claims;
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

  public String generate(String username, Long userId, Long projectId, java.util.List<String> roles) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + expirationMinutes * 60_000);
    return Jwts.builder()
        .subject(username)
        .claim("userId", userId)
        .claim("projectId", projectId)
        .claim("roles", roles)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(key)
        .compact();
  }

  public Claims parse(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
