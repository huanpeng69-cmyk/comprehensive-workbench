package com.workbench.ai.application.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 调用者身份快照的取值口径。
 *
 * <p>这一层被 {@link ToolInvoker} 的项目范围判定直接依赖：{@code hasProjectContext()} 判错一个方向，
 * 要么误拒公司级账号（COMPANY 档合法地没有 projectId），要么对没有项目上下文的账号放行、
 * 让 Agent 变成跨项目部查询的口子。故用测试把两种「合法上下文」与「无上下文」钉死。
 */
class ToolCallerTest {

  @Test
  void nullRolesBecomeEmptySet() {
    ToolCaller caller = new ToolCaller("alice", null, 7L, 1L, false);

    assertEquals(Set.of(), caller.roles(), "null 角色必须归一为空集，否则判定处到处要判空");
  }

  @Test
  void rolesAreCopiedAtConstruction() {
    Set<String> mutable = new LinkedHashSet<>(Set.of("HR"));
    ToolCaller caller = new ToolCaller("alice", mutable, 7L, 1L, false);

    mutable.add("ADMIN");

    assertEquals(Set.of("HR"), caller.roles(), "快照必须与来源集合解耦，事后改动不能影响已建身份");
  }

  @Test
  void companyScopeIsProjectContextEvenWithoutProjectId() {
    // COMPANY 档角色的 token 里没有 projectId，这是合法状态，不能当成「缺上下文」。
    ToolCaller companyWide = new ToolCaller("alice", Set.of(), null, 1L, true);

    assertTrue(companyWide.hasProjectContext());
  }

  @Test
  void boundProjectIsProjectContext() {
    ToolCaller projectBound = new ToolCaller("alice", Set.of(), 7L, 1L, false);

    assertTrue(projectBound.hasProjectContext());
  }

  @Test
  void neitherScopeNorProjectMeansNoContext() {
    ToolCaller anonymous = new ToolCaller(null, Set.of(), null, null, false);

    assertFalse(anonymous.hasProjectContext(), "两者皆无时必须判为无上下文，否则工具会被越权放行");
  }

  @Test
  void scopeSummaryCarriesAllThreeDimensions() {
    ToolCaller caller = new ToolCaller("alice", Set.of(), 7L, 1L, false);

    String summary = caller.scopeSummary();

    // 审计里要能一眼看出「这次调用落在哪个项目部、哪家单位、是否全公司范围」。
    assertTrue(summary.contains("projectId=7"), "实际：" + summary);
    assertTrue(summary.contains("companyId=1"), "实际：" + summary);
    assertTrue(summary.contains("companyScope=false"), "实际：" + summary);
  }
}
