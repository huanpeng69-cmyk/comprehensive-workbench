package com.workbench.ai.application.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.workbench.audit.application.AuditService;
import com.workbench.security.TraceContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 工具执行器的四条底线：角色不足不执行、超时能中断、异常不外溢、逐次留审计。
 *
 * <p>这些行为原来靠「工具自己自觉」（execute 不抛异常、RestTemplate 自带超时），
 * 本测试锁死「即便工具不守规矩，Agent 循环也不会被打断」。
 */
class ToolInvokerTest {

  private final AuditService auditService = mock(AuditService.class);
  private ExecutorService workers;
  private ToolInvoker invoker;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    workers = Executors.newFixedThreadPool(2);
    invoker = new ToolInvoker(auditService, workers);
  }

  @AfterEach
  void tearDown() {
    invoker.shutdown();
    SecurityContextHolder.clearContext();
    TraceContext.clear(null);
  }

  /** 默认调用者：有项目部上下文、无角色——覆盖「范围与角色都不设门槛」的基线用例。 */
  private static ToolCaller caller(Set<String> roles) {
    return new ToolCaller("alice", roles, 7L, 1L, false);
  }

  @Test
  void successWritesCallAndResultAudits() {
    StubTool tool = new StubTool("t_ok");

    String result = invoker.invoke(caller(Set.of()), tool, "{\"query\":\"报销制度\"}", Map.of(),
        new AtomicBoolean(false));

    assertEquals("t_ok 的结果", result, "工具输出必须原样回给模型");
    assertEquals(1, tool.calls());
    verify(auditService).log(eq("alice"), eq("AI_TOOL_CALL"), eq("AI_TOOL"), eq("t_ok"),
        contains("params={\"query\":\"报销制度\"}"));
    verify(auditService).log(eq("alice"), eq("AI_TOOL_RESULT"), eq("AI_TOOL"), eq("t_ok"),
        contains("OK"));
  }

  @Test
  void timeoutDegradesToTextInsteadOfBlocking() {
    StubTool tool = new StubTool("t_slow").withSleepMs(2_000).withTimeoutMs(50);

    long start = System.nanoTime();
    String result = invoker.invoke(caller(Set.of()), tool, "{}", Map.of(), new AtomicBoolean(false));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    assertTrue(result.startsWith("【工具超时】"), "超时必须降级为说明性文本，实际：" + result);
    assertTrue(elapsedMs < 1_000, "必须在工具超时附近返回，不能等工具自己睡醒，实际 " + elapsedMs + "ms");
    verify(auditService).failed(eq("alice"), eq("AI_TOOL_RESULT"), eq("AI_TOOL"), eq("t_slow"),
        contains("超时"), eq("504"));
  }

  @Test
  void toolExceptionIsCaughtAndReported() {
    StubTool tool = new StubTool("t_fail").withFail(true);

    String result = invoker.invoke(caller(Set.of()), tool, "{}", Map.of(), new AtomicBoolean(false));

    assertTrue(result.startsWith("【工具失败】"), "异常必须降级为说明性文本，实际：" + result);
    assertTrue(result.contains("boom"), "失败原因要带进降级文案，模型才知道改走别的路径");
    verify(auditService).failed(eq("alice"), eq("AI_TOOL_RESULT"), eq("AI_TOOL"), eq("t_fail"),
        contains("boom"), eq("500"));
  }

  @Test
  void nullResultNeverReachesTheModel() {
    StubTool tool = new StubTool("t_null").withNullResult(true);

    String result = invoker.invoke(caller(Set.of()), tool, "{}", Map.of(), new AtomicBoolean(false));

    // null 会变成 messages 里的 content:null，触发模型接口 400，必须在执行器里挡掉。
    assertTrue(result.startsWith("【工具空结果】"), "实际：" + result);
  }

  @Test
  void roleRuleBlocksExecutionAndRecordsDenied() {
    StubTool tool = new StubTool("t_hr").withRequiredRoles(Set.of("HR"));

    String result = invoker.invoke(caller(Set.of()), tool, "{}", Map.of(), new AtomicBoolean(false));

    assertTrue(result.startsWith("【无权限】"), "实际：" + result);
    assertEquals(0, tool.calls(), "角色不足时工具根本不能被执行");
    verify(auditService).denied(eq("alice"), eq("AI_TOOL_CALL"), eq("AI_TOOL"), eq("t_hr"),
        contains("角色不足"), eq("403"));
  }

  @Test
  void roleRuleAllowsMatchingRole() {
    StubTool tool = new StubTool("t_hr").withRequiredRoles(Set.of("HR"));

    String result = invoker.invoke(caller(Set.of("HR")), tool, "{}", Map.of(),
        new AtomicBoolean(false));

    assertEquals("t_hr 的结果", result);
    assertEquals(1, tool.calls());
  }

  @Test
  void rolesComeFromCallerNotFromSecurityContext() {
    // 回归：角色由调用方（Agent 主线程）取出后传参，执行器不能回头读 SecurityContext。
    // 这里故意把上下文设成 HR、传参给 ADMIN —— 工具线程里 ThreadLocal 是空的，
    // 一旦执行器改成「读上下文」，要么恒判无权限，要么误放行，两种都必须被抓出来。
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        "alice", "n/a", List.of(new SimpleGrantedAuthority("ROLE_HR"))));
    StubTool tool = new StubTool("t_hr").withRequiredRoles(Set.of("HR"));

    String result = invoker.invoke(caller(Set.of("ADMIN")), tool, "{}", Map.of(),
        new AtomicBoolean(false));

    assertTrue(result.startsWith("【无权限】"), "判定只能依据传参角色，实际：" + result);
    assertEquals(0, tool.calls(), "上下文里带着 HR 也不能放行：工具线程读不到它");
  }

  @Test
  void cancelledBeforeInvokeSkipsExecution() {
    StubTool tool = new StubTool("t_cancel");

    String result = invoker.invoke(caller(Set.of()), tool, "{}", Map.of(), new AtomicBoolean(true));

    assertTrue(result.startsWith("【已取消】"), "实际：" + result);
    assertEquals(0, tool.calls(), "客户端已断开时不应再发起工具调用");
    verify(auditService).denied(eq("alice"), eq("AI_TOOL_CALL"), eq("AI_TOOL"), eq("t_cancel"),
        contains("已取消"), eq("499"));
  }

  @Test
  void traceIdIsPropagatedToToolThread() {
    StubTool tool = new StubTool("t_trace");
    String prev = TraceContext.bind("trace-test-1");
    try {
      invoker.invoke(caller(Set.of()), tool, "{}", Map.of(), new AtomicBoolean(false));
    } finally {
      TraceContext.clear(prev);
    }

    // 工具在独立线程执行，MDC 不随线程池传递：不手动透传，工具日志就丢了链路 id。
    assertEquals("trace-test-1", tool.seenTrace());
  }

  @Test
  void projectScopeToolRejectedWithoutProjectContext() {
    // 声明了 needsProjectScope 的工具，在没有项目部、也没有全公司范围时必须拒绝：
    // 放行等于让 Agent 成为「跨项目部查询」的口子（README §4.2：业务数据按 project 隔离）。
    StubTool tool = new StubTool("t_scoped").withNeedsProjectScope(true);
    ToolCaller noScope = new ToolCaller("alice", Set.of(), null, 1L, false);

    String result = invoker.invoke(noScope, tool, "{}", Map.of(), new AtomicBoolean(false));

    assertTrue(result.startsWith("【无权限】"), "实际：" + result);
    assertTrue(result.contains("缺少项目部上下文"), "拒绝原因要说明缺的是项目上下文，实际：" + result);
    assertEquals(0, tool.calls(), "没有项目上下文时工具根本不能被执行");
    verify(auditService).denied(eq("alice"), eq("AI_TOOL_CALL"), eq("AI_TOOL"), eq("t_scoped"),
        contains("缺少项目部上下文"), eq("403"));
  }

  @Test
  void projectScopeToolAllowedForCompanyScope() {
    // COMPANY 档角色合法地没有 projectId：硬按「projectId 非空」判会误拒所有公司级账号。
    StubTool tool = new StubTool("t_scoped").withNeedsProjectScope(true);
    ToolCaller companyWide = new ToolCaller("alice", Set.of(), null, 1L, true);

    String result = invoker.invoke(companyWide, tool, "{}", Map.of(), new AtomicBoolean(false));

    assertEquals("t_scoped 的结果", result);
    assertEquals(1, tool.calls());
    verify(auditService).log(eq("alice"), eq("AI_TOOL_CALL"), eq("AI_TOOL"), eq("t_scoped"),
        contains("companyScope=true"));
  }

  @Test
  void projectScopeToolAllowedWithProjectId() {
    StubTool tool = new StubTool("t_scoped").withNeedsProjectScope(true);
    ToolCaller projectBound = new ToolCaller("alice", Set.of(), 7L, 1L, false);

    String result = invoker.invoke(projectBound, tool, "{}", Map.of(), new AtomicBoolean(false));

    assertEquals("t_scoped 的结果", result);
    assertEquals(1, tool.calls());
  }

  /** 可配置的桩工具：把各用例的差异（超时/抛异常/要求角色/返回 null）集中在一处。 */
  private static final class StubTool implements AgentTool {

    private final String name;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> seenTrace = new AtomicReference<>();
    private Set<String> requiredRoles = Set.of();
    private int timeoutMs = 5_000;
    private long sleepMs;
    private boolean fail;
    private boolean nullResult;
    /** 默认 false：多数用例考的是超时/审计/角色，不该被项目范围判定拦住。 */
    private boolean needsProjectScope;

    StubTool(String name) {
      this.name = name;
    }

    StubTool withRequiredRoles(Set<String> roles) {
      this.requiredRoles = roles;
      return this;
    }

    StubTool withTimeoutMs(int ms) {
      this.timeoutMs = ms;
      return this;
    }

    StubTool withSleepMs(long ms) {
      this.sleepMs = ms;
      return this;
    }

    StubTool withFail(boolean value) {
      this.fail = value;
      return this;
    }

    StubTool withNullResult(boolean value) {
      this.nullResult = value;
      return this;
    }

    StubTool withNeedsProjectScope(boolean value) {
      this.needsProjectScope = value;
      return this;
    }

    int calls() {
      return calls.get();
    }

    String seenTrace() {
      return seenTrace.get();
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String description() {
      return name;
    }

    @Override
    public Map<String, Object> parametersSchema() {
      return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public Set<String> requiredRoles() {
      return requiredRoles;
    }

    @Override
    public int timeoutMs() {
      return timeoutMs;
    }

    @Override
    public boolean needsProjectScope() {
      return needsProjectScope;
    }

    @Override
    public String execute(Map<String, Object> args) {
      calls.incrementAndGet();
      seenTrace.set(TraceContext.get());
      if (sleepMs > 0) {
        try {
          Thread.sleep(sleepMs);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return "【中断】";
        }
      }
      if (fail) {
        throw new IllegalStateException("boom");
      }
      return nullResult ? null : name + " 的结果";
    }
  }
}
