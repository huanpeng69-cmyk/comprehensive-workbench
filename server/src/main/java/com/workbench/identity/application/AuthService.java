package com.workbench.identity.application;

import com.workbench.identity.application.dto.LoginRequest;
import com.workbench.identity.application.dto.LoginResponse;
import com.workbench.identity.domain.User;
import com.workbench.identity.repository.OrgUnitRepository;
import com.workbench.identity.repository.UserRepository;
import com.workbench.security.JwtUtil;
import java.util.List;
import java.time.Duration;
import java.util.Locale;
import com.workbench.audit.application.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号密码登录：失败计数防暴力破解 → 校验账号 → 签发 JWT（含角色码与项目部）→ 全程审计留痕。
 */
@Service
public class AuthService {

  private final UserRepository userRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtUtil jwtUtil;

  private final AuditService auditService;
  private final StringRedisTemplate redis;

  @Value("${workbench.login.max-failures:5}")
  private int maxFailures;

  @Value("${workbench.login.lock-minutes:15}")
  private long lockMinutes;

  public AuthService(UserRepository userRepository, OrgUnitRepository orgUnitRepository,
      PasswordEncoder passwordEncoder, JwtUtil jwtUtil, AuditService auditService,
      StringRedisTemplate redis) {
    this.userRepository = userRepository;
    this.orgUnitRepository = orgUnitRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtUtil = jwtUtil;
    this.auditService = auditService;
    this.redis = redis;
  }

  @Transactional(readOnly = true)
  public LoginResponse login(LoginRequest request) {
    String username = request.username();
    String failKey = "wb:login:fail:" + username.toLowerCase(Locale.ROOT);
    String failures = redis.opsForValue().get(failKey);
    if (failures != null && Integer.parseInt(failures) >= maxFailures) {
      auditService.log(username, "LOGIN_REJECTED", "USER", username,
          "账号因连续密码错误被锁定 " + lockMinutes + " 分钟");
      throw new IllegalStateException("密码连续错误次数过多，请 " + lockMinutes + " 分钟后再试");
    }

    User user = userRepository.findForLogin(request.username())
        .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
    // 用户名不存在也计数，否则可用「不存在的用户名」探测账号是否存在且不被锁定
    redis.opsForValue().increment(failKey);
    redis.expire(failKey, Duration.ofMinutes(lockMinutes));
    if (!Boolean.TRUE.equals(user.getEnabled())) {
      throw new IllegalStateException("账号已禁用");
    }
    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      auditService.log(username, "LOGIN_FAILURE", "USER", username, "密码错误");
      throw new IllegalArgumentException("用户名或密码错误");
    }
    redis.delete(failKey);
    List<String> roleCodes = user.getRoles().stream()
        .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
        .map(r -> r.getCode())
        .toList();
    String token = jwtUtil.generate(user.getUsername(), user.getId(), user.getProjectId(), roleCodes);
    String projectName = orgUnitRepository.findById(user.getProjectId())
        .map(org -> org.getName())
        .orElse(null);
    auditService.log(username, "LOGIN_SUCCESS", "USER", username, "登录成功");
    return new LoginResponse(
        token,
        user.getId(),
        user.getUsername(),
        user.getName(),
        user.getProjectId(),
        projectName,
        roleCodes);
  }
}
