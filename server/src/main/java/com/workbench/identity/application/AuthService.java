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
import com.workbench.platform.identity.PermissionQueryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号密码登录：失败计数防暴力破解 → 按单位定位账号 → 校验密码 → 签发 JWT（含角色码、单位与项目部）→ 全程审计留痕。
 */
@Service
public class AuthService {

  private final UserRepository userRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtUtil jwtUtil;

  private final AuditService auditService;
  private final StringRedisTemplate redis;
  private final PermissionQueryPort permissionQueryPort;

  @Value("${workbench.login.max-failures:5}")
  private int maxFailures;

  @Value("${workbench.login.lock-minutes:15}")
  private long lockMinutes;

  public AuthService(UserRepository userRepository, OrgUnitRepository orgUnitRepository,
      PasswordEncoder passwordEncoder, JwtUtil jwtUtil, AuditService auditService,
      StringRedisTemplate redis, PermissionQueryPort permissionQueryPort) {
    this.userRepository = userRepository;
    this.orgUnitRepository = orgUnitRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtUtil = jwtUtil;
    this.auditService = auditService;
    this.redis = redis;
    this.permissionQueryPort = permissionQueryPort;
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

    List<User> candidates = userRepository.findAllForLoginByUsername(request.username());
    // 用户名不存在也计数，否则可用「不存在的用户名」探测账号是否存在且不被锁定
    redis.opsForValue().increment(failKey);
    redis.expire(failKey, Duration.ofMinutes(lockMinutes));
    User user = resolveLoginUser(candidates, request.companyId(), username);
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
    // 权限点随 token 下发，供 @PreAuthorize 把关后台写操作（阶段 3，ADR-M010）。
    // 角色为空时不查库（与 RoleBasedDataScopeResolver 同口径，避免无角色用户触发无谓查询）。
    List<String> permissions = roleCodes.isEmpty()
        ? List.of()
        : List.copyOf(permissionQueryPort.findByRoleCodes(roleCodes));
    String token = jwtUtil.generate(user.getUsername(), user.getId(), user.getCompanyId(),
        user.getProjectId(), roleCodes, permissions);
    // projectId 为 null 的账号（只有单位、没有项目部，如公司本部工会主席/财务）必须先判空：
    // JpaRepository.findById(null) 会抛 InvalidDataAccessApiUsageException，被 GlobalExceptionHandler
    // 转成 500 且不留日志与审计（2026-10-14 实测，ADR-M008 遗留项②）。与 UserService#toResponse 口径一致。
    String projectName = (user.getProjectId() != null)
        ? orgUnitRepository.findById(user.getProjectId()).map(org -> org.getName()).orElse(null)
        : null;
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

  /**
   * 在候选账号中定位本次登录的账号。
   *
   * <p>阶段 2 起用户名只在单位内唯一（ADR-M006）：</p>
   * <ol>
   *   <li>请求带了 {@code companyId} → 只在该单位内匹配；匹配不到按「用户名或密码错误」处理，
   *       不额外泄露该账号在别家单位是否存在；</li>
   *   <li>请求没带 {@code companyId} 且唯一命中 → 直接用（前端本期不传单位，账号不重名时行为不变）；</li>
   *   <li>请求没带 {@code companyId} 且多命中 → 记 {@code LOGIN_REJECTED} 审计并报可读错误。
   *       <b>不得任选一个</b>，否则会把用户登进别的单位。</li>
   * </ol>
   */
  private User resolveLoginUser(List<User> candidates, Long companyId, String username) {
    if (companyId != null) {
      return candidates.stream()
          .filter(candidate -> companyId.equals(candidate.getCompanyId()))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
    }
    if (candidates.isEmpty()) {
      throw new IllegalArgumentException("用户名或密码错误");
    }
    if (candidates.size() > 1) {
      auditService.log(username, "LOGIN_REJECTED", "USER", username,
          "用户名在多个单位下存在，需指定所属单位");
      throw new IllegalStateException("该用户名在多个单位下存在，请指定所属单位后重试");
    }
    return candidates.get(0);
  }
}
