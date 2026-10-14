package com.workbench.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.workbench.identity.domain.User;
import com.workbench.identity.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 阶段 2 端口适配器的单位维度（ADR-M006）：用户名只在单位内唯一之后，
 * 适配器必须把「单位」原样带给仓储；companyId 缺失时不得跨单位猜测。
 *
 * <p>本测试锁的是适配器与仓储之间的契约（少传/传错单位都会变红），
 * 真实 SQL 语义由 {@code SchemaBaselineIT} 的唯一约束与查询断言覆盖。</p>
 */
class IdentityUserQueryAdapterTest {

  private static final long COMPANY_ID = 4L;

  private final UserRepository userRepository = mock(UserRepository.class);
  private final IdentityUserQueryAdapter adapter = new IdentityUserQueryAdapter(userRepository);

  @Test
  void findByCompanyIdAndUsernameScopesToCompany() {
    when(userRepository.findByCompanyIdAndUsername(COMPANY_ID, "chen"))
        .thenReturn(Optional.of(user("chen", "陈国栋")));

    assertEquals("陈国栋",
        adapter.findByCompanyIdAndUsername(COMPANY_ID, "chen").orElseThrow().name());
    verify(userRepository).findByCompanyIdAndUsername(COMPANY_ID, "chen");
  }

  @Test
  void findByCompanyIdAndUsernameWithoutCompanyReturnsEmptyWithoutQuerying() {
    assertTrue(adapter.findByCompanyIdAndUsername(null, "chen").isEmpty(),
        "单位未知时必须查不到（调用方据此拒绝），不得退化成跨单位查同名账号");
    verifyNoInteractions(userRepository);
  }

  @Test
  void existsByCompanyIdAndUsernameScopesToCompany() {
    when(userRepository.existsByCompanyIdAndUsername(COMPANY_ID, "chen")).thenReturn(true);

    assertTrue(adapter.existsByCompanyIdAndUsername(COMPANY_ID, "chen"));
    verify(userRepository).existsByCompanyIdAndUsername(COMPANY_ID, "chen");
  }

  @Test
  void existsByCompanyIdAndUsernameWithoutCompanyIsFalseWithoutQuerying() {
    assertFalse(adapter.existsByCompanyIdAndUsername(null, "chen"));
    verifyNoInteractions(userRepository);
  }

  @Test
  void findEnabledUsernamesByRoleCodeAndCompanyIdScopesToCompany() {
    when(userRepository.findEnabledByRoleCodeAndCompanyId("PROJECT_MANAGER", COMPANY_ID))
        .thenReturn(List.of(user("chen", "陈国栋"), user("li", "李明远")));

    assertEquals(List.of("chen", "li"),
        adapter.findEnabledUsernamesByRoleCodeAndCompanyId("PROJECT_MANAGER", COMPANY_ID));
    verify(userRepository).findEnabledByRoleCodeAndCompanyId("PROJECT_MANAGER", COMPANY_ID);
  }

  @Test
  void findEnabledUsernamesByRoleCodeAndCompanyIdWithoutCompanyReturnsEmpty() {
    // 单位未知时返回空：审批人解析的结果应当是「本单位没人可批」，不能跨公司抓人。
    assertTrue(adapter.findEnabledUsernamesByRoleCodeAndCompanyId("PROJECT_MANAGER", null)
        .isEmpty());
    verifyNoInteractions(userRepository);
  }

  private static User user(String username, String name) {
    return User.builder().username(username).name(name).enabled(true).companyId(COMPANY_ID).build();
  }
}
