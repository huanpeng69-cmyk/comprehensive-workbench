package com.workbench.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.workbench.audit.application.AuditService;
import com.workbench.identity.application.dto.LoginRequest;
import com.workbench.identity.application.dto.LoginResponse;
import com.workbench.identity.domain.OrgUnit;
import com.workbench.identity.domain.Role;
import com.workbench.identity.domain.User;
import com.workbench.identity.repository.OrgUnitRepository;
import com.workbench.identity.repository.UserRepository;
import com.workbench.platform.identity.PermissionQueryPort;
import com.workbench.security.JwtUtil;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 中台化回归基线（阶段 2，identity 模块）：登录时的单位定位。
 *
 * <p>阶段 2 把用户名唯一性从全局唯一改成 {@code (company_id, username)} 复合唯一（ADR-M006），
 * 于是「一个用户名可能命中多家单位」，{@code login} 必须正确定位：</p>
 * <ol>
 *   <li>请求带 {@code companyId} → 只在该单位内匹配（<b>不得</b>取第一条，否则会把用户登进别家单位）；</li>
 *   <li>请求不带 {@code companyId} 且唯一命中 → 直接用（前端本期不传单位，账号不重名时行为不变）；</li>
 *   <li>请求不带 {@code companyId} 且多命中 → 拒绝 + {@code LOGIN_REJECTED} 审计，不签发 token。</li>
 * </ol>
 *
 * <p>同时锁定未受影响的既有语义（失败计数、账号禁用、密码错误审计、锁定门槛），
 * 它们在阶段 2 里逐字未改。</p>
 */
class AuthServiceTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final OrgUnitRepository orgUnitRepository = mock(OrgUnitRepository.class);
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  private final JwtUtil jwtUtil = mock(JwtUtil.class);
  private final AuditService auditService = mock(AuditService.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final PermissionQueryPort permissionQueryPort = mock(PermissionQueryPort.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> valueOps =
      (ValueOperations<String, String>) mock(ValueOperations.class);

  private final AuthService service = new AuthService(userRepository, orgUnitRepository,
      passwordEncoder, jwtUtil, auditService, redis, permissionQueryPort);

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(valueOps);
    ReflectionTestUtils.setField(service, "maxFailures", 5);
    ReflectionTestUtils.setField(service, "lockMinutes", 15L);
    when(permissionQueryPort.findByRoleCodes(any())).thenReturn(Set.of());
  }

  private static User user(long id, String username, long companyId, Long projectId,
      String passwordHash, boolean enabled, String... roleCodes) {
    Set<Role> roles = Arrays.stream(roleCodes)
        .map(code -> Role.builder().code(code).name(code).dataScope("PROJECT").enabled(true).build())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return User.builder()
        .id(id)
        .username(username)
        .name("姓名-" + username)
        .passwordHash(passwordHash)
        .companyId(companyId)
        .projectId(projectId)
        .enabled(enabled)
        .roles(roles)
        .build();
  }

  @Test
  void singleMatchWithoutCompanyIdKeepsLegacyBehaviour() {
    User user = user(7L, "chenguodong", 1L, 3L, "hash", true, "PROJECT_MANAGER");
    when(userRepository.findAllForLoginByUsername("chenguodong")).thenReturn(List.of(user));
    when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
    when(orgUnitRepository.findById(3L)).thenReturn(Optional.of(OrgUnit.builder().id(3L)
        .name("天河项目部").type("PROJECT").build()));
    when(jwtUtil.generate(anyString(), anyLong(), anyLong(), any(), any(), any())).thenReturn("token-1");

    LoginResponse response = service.login(new LoginRequest("chenguodong", "pw", null));

    assertEquals("token-1", response.token());
    assertEquals(List.of("PROJECT_MANAGER"), response.roles());
    assertEquals("天河项目部", response.projectName());
    verify(jwtUtil).generate("chenguodong", 7L, 1L, 3L, List.of("PROJECT_MANAGER"), List.of());
    verify(redis).delete("wb:login:fail:chenguodong");
  }

  @Test
  void companyIdSelectsTheRightAccountWhenUsernameCollides() {
    User south = user(7L, "admin", 1L, 3L, "hash-south", true, "SYSTEM_ADMIN");
    User north = user(8L, "admin", 2L, 4L, "hash-north", true, "EMPLOYEE");
    when(userRepository.findAllForLoginByUsername("admin")).thenReturn(List.of(south, north));
    when(passwordEncoder.matches("pw", "hash-north")).thenReturn(true);
    when(jwtUtil.generate(anyString(), anyLong(), anyLong(), any(), any(), any())).thenReturn("token-north");

    LoginResponse response = service.login(new LoginRequest("admin", "pw", 2L));

    assertEquals("token-north", response.token());
    verify(jwtUtil).generate("admin", 8L, 2L, 4L, List.of("EMPLOYEE"), List.of());
  }

  @Test
  void usernameCollisionWithoutCompanyIdIsRejectedInsteadOfPickingFirst() {
    User south = user(7L, "admin", 1L, 3L, "hash-south", true, "SYSTEM_ADMIN");
    User north = user(8L, "admin", 2L, 4L, "hash-north", true, "EMPLOYEE");
    when(userRepository.findAllForLoginByUsername("admin")).thenReturn(List.of(south, north));

    assertThrows(IllegalStateException.class,
        () -> service.login(new LoginRequest("admin", "pw", null)));

    verify(auditService).log("admin", "LOGIN_REJECTED", "USER", "admin",
        "用户名在多个单位下存在，需指定所属单位");
    verify(passwordEncoder, never()).matches(anyString(), anyString());
    verify(jwtUtil, never()).generate(anyString(), any(), any(), any(), any(), any());
  }

  @Test
  void companyIdWithoutMatchingAccountFailsGenerically() {
    User south = user(7L, "admin", 1L, 3L, "hash-south", true, "SYSTEM_ADMIN");
    when(userRepository.findAllForLoginByUsername("admin")).thenReturn(List.of(south));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> service.login(new LoginRequest("admin", "pw", 99L)));

    assertEquals("用户名或密码错误", error.getMessage());
    verify(passwordEncoder, never()).matches(anyString(), anyString());
  }

  @Test
  void unknownUsernameIsCountedWithoutRevealingExistence() {
    when(userRepository.findAllForLoginByUsername("ghost")).thenReturn(List.of());

    assertThrows(IllegalArgumentException.class,
        () -> service.login(new LoginRequest("ghost", "pw", null)));

    verify(valueOps).increment("wb:login:fail:ghost");
    verify(redis).expire(eq("wb:login:fail:ghost"), eq(Duration.ofMinutes(15)));
    verify(auditService, never()).log(anyString(), anyString(), anyString(), anyString(),
        anyString());
  }

  @Test
  void disabledAccountIsRejectedAndNotCountedAsPasswordFailure() {
    User user = user(7L, "chenguodong", 1L, 3L, "hash", false, "EMPLOYEE");
    when(userRepository.findAllForLoginByUsername("chenguodong")).thenReturn(List.of(user));

    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> service.login(new LoginRequest("chenguodong", "pw", null)));

    assertEquals("账号已禁用", error.getMessage());
    verify(jwtUtil, never()).generate(anyString(), any(), any(), any(), any(), any());
  }

  @Test
  void wrongPasswordIsAuditedAndTokenNotIssued() {
    User user = user(7L, "chenguodong", 1L, 3L, "hash", true, "EMPLOYEE");
    when(userRepository.findAllForLoginByUsername("chenguodong")).thenReturn(List.of(user));
    when(passwordEncoder.matches("bad", "hash")).thenReturn(false);

    assertThrows(IllegalArgumentException.class,
        () -> service.login(new LoginRequest("chenguodong", "bad", null)));

    verify(auditService).log("chenguodong", "LOGIN_FAILURE", "USER", "chenguodong", "密码错误");
    verify(jwtUtil, never()).generate(anyString(), any(), any(), any(), any(), any());
    verify(redis, never()).delete(anyString());
  }

  @Test
  void lockedUsernameIsRejectedBeforeAnyAccountLookup() {
    when(valueOps.get("wb:login:fail:chenguodong")).thenReturn("5");

    assertThrows(IllegalStateException.class,
        () -> service.login(new LoginRequest("chenguodong", "pw", null)));

    verifyNoInteractions(userRepository);
    verify(auditService).log("chenguodong", "LOGIN_REJECTED", "USER", "chenguodong",
        "账号因连续密码错误被锁定 15 分钟");
  }

  @Test
  void accountWithoutProjectLogsInWithoutTouchingOrgUnitRepository() {
    // ADR-M008 遗留项②（D1）：只有单位、没有项目部的账号（如公司本部工会主席）登录时
    // 不得再拿 projectId=null 去查 org_unit —— 那会抛 InvalidDataAccessApiUsageException，
    // 被 GlobalExceptionHandler 转成 500，且不留日志与审计。
    User user = user(9L, "union_hd", 1L, null, "hash", true, "UNION_LEADER");
    when(userRepository.findAllForLoginByUsername("union_hd")).thenReturn(List.of(user));
    when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
    when(jwtUtil.generate(anyString(), anyLong(), anyLong(), any(), any(), any())).thenReturn("token-union");

    LoginResponse response = service.login(new LoginRequest("union_hd", "pw", null));

    assertEquals("token-union", response.token());
    assertNull(response.projectId());
    assertNull(response.projectName());
    verify(jwtUtil).generate("union_hd", 9L, 1L, null, List.of("UNION_LEADER"), List.of());
    verify(orgUnitRepository, never()).findById(any());
    verify(auditService).log("union_hd", "LOGIN_SUCCESS", "USER", "union_hd", "登录成功");
  }

  @Test
  void companyScopedAccountWithStaleProjectIdStillLogsInWithNullProjectName() {
    User user = user(10L, "fangjing", 1L, 2L, "hash", true, "FINANCE");
    when(userRepository.findAllForLoginByUsername("fangjing")).thenReturn(List.of(user));
    when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
    when(jwtUtil.generate(anyString(), anyLong(), anyLong(), any(), any(), any())).thenReturn("token-2");
    when(orgUnitRepository.findById(2L)).thenReturn(Optional.empty());

    LoginResponse response = service.login(new LoginRequest("fangjing", "pw", null));

    assertEquals("token-2", response.token());
    assertEquals(2L, response.projectId());
    assertNull(response.projectName());
    verify(auditService).log("fangjing", "LOGIN_SUCCESS", "USER", "fangjing", "登录成功");
  }
}
