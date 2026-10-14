package com.workbench.platform.datascope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.workbench.platform.org.RoleQueryPort;
import com.workbench.platform.org.RoleRef;
import com.workbench.testsupport.SecurityContextFixture;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 中台化回归基线（G1，平台内核）：数据范围判定的唯一收口 {@link RoleBasedDataScopeResolver}。
 *
 * <p>与 5 个 {@code *ServiceDataScopeTest}（它们用真实本类 + mock RoleQueryPort 做端到端锁定）互补，
 * 这里直接锁本类的判定语义。以下每一条都对应阶段 1 之前散落在各业务服务里的
 * {@code callerHasCompanyScope} / {@code assertCompanyScope} 行为，改语义必须同步改两处并说明原因：</p>
 * <ol>
 *   <li>角色码为空 → false，<b>且不查角色表</b>（无角色用户不得触发查询）；</li>
 *   <li>角色码按当前用户权限原样传给端口；</li>
 *   <li>只认 {@code enabled == true} 的角色；</li>
 *   <li>{@code dataScope} 用字符串 {@code "COMPANY"} 精确比较（不做 trim / 大小写归一）；</li>
 *   <li>{@code currentProjectId()} 取 JWT Claims 的 {@code projectId}；</li>
 *   <li>{@code canAccessProject}：COMPANY 全放行；否则要求双方 projectId 非空且相等
 *       （记录侧为 null 时拒绝）。</li>
 *   <li>{@code currentCompanyId()} 取 JWT Claims 的 {@code companyId}（阶段 2 新增；null = 未指定，
 *       旧 token 即此情形，调用方必须显式处理而不是当成「不过滤」）。</li>
 * </ol>
 */
class RoleBasedDataScopeResolverTest {

  private static final long PROJECT_ID = 7L;
  private static final long OTHER_PROJECT_ID = 9L;
  private static final long COMPANY_ID = 4L;

  private final RoleQueryPort roleQueryPort = mock(RoleQueryPort.class);
  private final DataScopeResolver resolver = new RoleBasedDataScopeResolver(roleQueryPort);

  @AfterEach
  void tearDown() {
    SecurityContextFixture.clear();
  }

  private static RoleRef role(String code, String dataScope, boolean enabled) {
    return new RoleRef(code, dataScope, enabled);
  }

  @Test
  void noRolesMeansNoCompanyScopeAndNoRoleQuery() {
    SecurityContextFixture.runAs("nobody", null);

    assertFalse(resolver.hasCompanyScope());
    verifyNoInteractions(roleQueryPort);
  }

  @Test
  void passesCurrentUserRoleCodesToPort() {
    SecurityContextFixture.runAs("u", null, "PROJECT_MANAGER", "GM");
    when(roleQueryPort.findByCodes(List.of("PROJECT_MANAGER", "GM"))).thenReturn(List.of());

    assertFalse(resolver.hasCompanyScope());
    verify(roleQueryPort).findByCodes(List.of("PROJECT_MANAGER", "GM"));
  }

  @Test
  void enabledCompanyRoleGrantsCompanyScope() {
    SecurityContextFixture.runAs("gm", null, "GM");
    when(roleQueryPort.findByCodes(List.of("GM"))).thenReturn(List.of(role("GM", "COMPANY", true)));

    assertTrue(resolver.hasCompanyScope());
  }

  @Test
  void disabledCompanyRoleDoesNotGrantCompanyScope() {
    SecurityContextFixture.runAs("gm", PROJECT_ID, "GM");
    when(roleQueryPort.findByCodes(List.of("GM")))
        .thenReturn(List.of(role("GM", "COMPANY", false)));

    assertFalse(resolver.hasCompanyScope(), "停用角色不得放行（旧逻辑只认 enabled == true）");
  }

  @Test
  void anyEnabledCompanyRoleAmongSeveralGrantsScope() {
    SecurityContextFixture.runAs("u", PROJECT_ID, "PROJECT_MANAGER", "GM");
    when(roleQueryPort.findByCodes(List.of("PROJECT_MANAGER", "GM"))).thenReturn(List.of(
        role("PROJECT_MANAGER", "PROJECT", true), role("GM", "COMPANY", true)));

    assertTrue(resolver.hasCompanyScope());
  }

  @Test
  void projectScopeRoleDoesNotGrantCompanyScope() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");
    when(roleQueryPort.findByCodes(List.of("PROJECT_MANAGER")))
        .thenReturn(List.of(role("PROJECT_MANAGER", "PROJECT", true)));

    assertFalse(resolver.hasCompanyScope());
  }

  @Test
  void dataScopeComparisonIsExactStringMatch() {
    SecurityContextFixture.runAs("gm", null, "GM");
    when(roleQueryPort.findByCodes(List.of("GM")))
        .thenReturn(List.of(role("GM", "company", true)));

    assertFalse(resolver.hasCompanyScope(), "旧逻辑是 \"COMPANY\".equals(...)，不做大小写归一");
  }

  @Test
  void roleMissingFromRoleTableIsNotGranted() {
    SecurityContextFixture.runAs("ghost", null, "GM");
    when(roleQueryPort.findByCodes(List.of("GM"))).thenReturn(List.of());

    assertFalse(resolver.hasCompanyScope(), "权限里带了角色码但角色表查不到（或已停用）不得放行");
  }

  @Test
  void currentProjectIdReadsProjectIdClaim() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");

    assertEquals(PROJECT_ID, resolver.currentProjectId());
  }

  @Test
  void currentProjectIdIsNullWhenClaimAbsent() {
    SecurityContextFixture.runAs("gm", null, "GM");

    assertNull(resolver.currentProjectId());
  }

  @Test
  void canAccessProjectAlwaysTrueForCompanyScope() {
    SecurityContextFixture.runAs("gm", null, "GM");
    when(roleQueryPort.findByCodes(List.of("GM"))).thenReturn(List.of(role("GM", "COMPANY", true)));

    assertTrue(resolver.canAccessProject(null));
    assertTrue(resolver.canAccessProject(PROJECT_ID));
    assertTrue(resolver.canAccessProject(OTHER_PROJECT_ID));
  }

  @Test
  void canAccessProjectAllowsOwnProjectOnly() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");
    when(roleQueryPort.findByCodes(List.of("PROJECT_MANAGER")))
        .thenReturn(List.of(role("PROJECT_MANAGER", "PROJECT", true)));

    assertTrue(resolver.canAccessProject(PROJECT_ID));
    assertFalse(resolver.canAccessProject(OTHER_PROJECT_ID));
  }

  @Test
  void canAccessProjectDeniesNullRecordProjectIdForProjectScope() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");
    when(roleQueryPort.findByCodes(List.of("PROJECT_MANAGER")))
        .thenReturn(List.of(role("PROJECT_MANAGER", "PROJECT", true)));

    assertFalse(resolver.canAccessProject(null), "记录侧无项目部归属时 PROJECT 用户不得放行");
  }

  @Test
  void canAccessProjectDeniesWhenUserHasNoProjectId() {
    SecurityContextFixture.runAs("pm", null, "PROJECT_MANAGER");
    when(roleQueryPort.findByCodes(List.of("PROJECT_MANAGER")))
        .thenReturn(List.of(role("PROJECT_MANAGER", "PROJECT", true)));

    assertFalse(resolver.canAccessProject(PROJECT_ID), "用户无项目部归属（严格模式）不得放行");
    verify(roleQueryPort).findByCodes(List.of("PROJECT_MANAGER"));
  }
  @Test
  void currentCompanyIdReadsCompanyIdClaim() {
    SecurityContextFixture.runAsInCompany("pm", COMPANY_ID, PROJECT_ID, "PROJECT_MANAGER");

    assertEquals(COMPANY_ID, resolver.currentCompanyId());
    verifyNoInteractions(roleQueryPort);
  }

  @Test
  void currentCompanyIdIsNullWhenClaimAbsent() {
    SecurityContextFixture.runAs("pm", PROJECT_ID, "PROJECT_MANAGER");

    assertNull(resolver.currentCompanyId(), "阶段 2 之前的旧 token 不带 companyId claim");
    verifyNoInteractions(roleQueryPort);
  }
}
