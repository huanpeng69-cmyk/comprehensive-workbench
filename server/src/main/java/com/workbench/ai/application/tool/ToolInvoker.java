package com.workbench.ai.application.tool;

import com.workbench.audit.application.AuditService;
import com.workbench.security.TraceContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 工具的受治理执行器。
 *
 * <p>把三件事从编排逻辑（{@code AgentService}）里收敛到一处：
 * <ol>
 *   <li><b>调用前授权</b>：按工具自己声明的 {@link AgentTool#requiredRoles()} 比对当前登录用户的角色，
 *       不足则拒绝并留 {@code DENIED} 审计——模型被诱导去调不该调的工具时挡在工具层，
 *       而不是指望提示词写「不要调用」；</li>
 *   <li><b>超时与失败隔离</b>：工具在独立线程池执行，编排线程最多等 {@link AgentTool#timeoutMs()}；
 *       工具抛异常、卡住、被中断都只降级成一段说明性文本回给模型，不打断整轮对话
 *       （延续 {@link AgentTool} 「execute 不抛异常」的契约，但不再依赖工具自觉）；</li>
 *   <li><b>逐次审计</b>：{@code AI_TOOL_CALL}（参数摘要）与 {@code AI_TOOL_RESULT}（成功/耗时/失败原因）
 *       两条独立记录，配合 {@code AuditService} 自动带的 {@code result}/{@code traceId}，
 *       可按一次对话的 traceId 还原「调了哪些工具、成没成」。审计只记参数摘要与结果规模，
 *       不记工具正文，与 {@code AI_AGENT} 总账「不记录对话正文」的取舍一致。</li>
 * </ol>
 *
 * <p><b>线程与上下文</b>：工具线程只透传 traceId（{@link TraceContext#bind}），
 * <b>不恢复</b> SecurityContext——工具的身份（username / 角色 / 项目部 / 单位）必须由编排层在
 * <b>主线程</b>取出后显式传入。工具线程内<b>不得</b>回读安全上下文：那里是空的（ThreadLocal 不跨线程池），
 * 回读会让角色判定恒为空、把「谁能看到哪些数据」重新变成隐式依赖。
 *
 * <p><b>取消语义</b>：{@code cancelled} 为 true 时<b>后续</b>工具不再执行；
 * 已在跑的工具靠 {@link Future#cancel(boolean)} + 自身超时兜底（阻塞中的 HTTP I/O 不响应中断）。
 */
public class ToolInvoker {

  /** 调用前审计 action：准备执行某工具（含参数摘要）。 */
  static final String ACTION_CALL = "AI_TOOL_CALL";
  /** 执行后审计 action：工具结果（成功/耗时，或失败原因）。 */
  static final String ACTION_RESULT = "AI_TOOL_RESULT";
  /** 审计目标类型：所有工具记录统一为 AI_TOOL，targetId 才是工具名。 */
  static final String TARGET_TYPE = "AI_TOOL";

  /** 与 AgentService 的流式线程池对齐：8 个 Agent 线程各占一个工具槽，排队时间不会算进工具超时。 */
  private static final int WORKER_THREADS = 8;
  private static final int QUEUE_CAPACITY = 64;
  /** 参数摘要上限：审计要能定位「问了什么」，又不能把长参数整段复制进审计表。 */
  private static final int SUMMARY_LIMIT = 200;
  private static final int FAILURE_DETAIL_LIMIT = 200;

  private static final Logger log = LoggerFactory.getLogger(ToolInvoker.class);
  private static final AtomicInteger WORKER_SEQ = new AtomicInteger();

  private final AuditService auditService;
  private final ExecutorService workers;

  public ToolInvoker(AuditService auditService) {
    this(auditService, new ThreadPoolExecutor(
        WORKER_THREADS, WORKER_THREADS, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(QUEUE_CAPACITY), ToolInvoker::newWorker,
        new ThreadPoolExecutor.AbortPolicy()));
  }

  /** 包级构造器供测试注入受控线程池。 */
  ToolInvoker(AuditService auditService, ExecutorService workers) {
    this.auditService = auditService;
    this.workers = workers;
  }

  private static Thread newWorker(Runnable r) {
    Thread t = new Thread(r, "ai-tool-invoker-" + WORKER_SEQ.incrementAndGet());
    // 守护线程：应用关停时不因为残留的工具调用阻塞 JVM 退出。
    t.setDaemon(true);
    return t;
  }

  /**
   * 执行一次工具调用，返回喂回模型的文本。<b>本方法不抛异常</b>：任何失败都降级为
   * {@code 【工具超时】/【工具失败】/【无权限】…} 开头的说明性文本。
   *
   * @param caller    调用者身份快照（用户名 / 角色 / 项目部 / 单位 / 是否全公司范围）。
   *                  这些值必须在<b>主线程</b>由编排层从安全上下文取出后显式传入，在工具线程回读只会拿到空值；
   *                  为 null 视为「无身份、无项目上下文」
   * @param tool      要执行的工具
   * @param rawArgs   模型给出的原始参数 JSON（用于审计摘要，可为 null）
   * @param args      已解析的参数（交给 {@link AgentTool#execute}）
   * @param cancelled 客户端是否已断开；为 true 时不再执行（可为 null，视为未取消）
   */
  public String invoke(ToolCaller caller, AgentTool tool, String rawArgs,
      Map<String, Object> args, AtomicBoolean cancelled) {
    String username = caller == null ? null : caller.username();
    String name = tool.name();
    String summary = summarizeArgs(rawArgs);

    if (cancelled != null && cancelled.get()) {
      auditSafely(() -> auditService.denied(username, ACTION_CALL, TARGET_TYPE, name,
          "调用前已取消（客户端断开），未执行。参数=" + summary, "499"));
      return "【已取消】客户端已断开，工具 " + name + " 未执行。";
    }

    Set<String> requiredRoles = tool.requiredRoles();
    Set<String> roles = caller == null ? Set.of() : caller.roles();
    if (requiredRoles != null && !requiredRoles.isEmpty()
        && roles.stream().noneMatch(requiredRoles::contains)) {
      String roleDetail = "角色不足：需要 " + requiredRoles + "，当前 " + (roles.isEmpty() ? "无" : roles);
      auditSafely(() -> auditService.denied(username, ACTION_CALL, TARGET_TYPE, name,
          roleDetail + "。参数=" + summary, "403"));
      return "【无权限】当前账号无权调用工具 " + name + "（" + roleDetail + "）。";
    }

    if (tool.needsProjectScope() && (caller == null || !caller.hasProjectContext())) {
      String scopeDetail = "缺少项目部上下文：该工具只在指定项目部或全公司范围内可用，当前"
          + (caller == null ? "未识别调用者" : caller.scopeSummary());
      auditSafely(() -> auditService.denied(username, ACTION_CALL, TARGET_TYPE, name,
          scopeDetail + "。参数=" + summary, "403"));
      return "【无权限】当前账号无权调用工具 " + name + "（" + scopeDetail + "）。";
    }

    auditSafely(() -> auditService.log(username, ACTION_CALL, TARGET_TYPE, name,
        "scope=" + (tool.needsProjectScope() ? "PROJECT" : "GLOBAL")
            + ", caller=" + (caller == null ? "anonymous" : caller.scopeSummary())
            + ", readOnly=" + tool.readOnly()
            + ", timeoutMs=" + tool.timeoutMs()
            + ", roles=" + (requiredRoles == null || requiredRoles.isEmpty() ? "any" : requiredRoles)
            + ", params=" + summary));

    int timeoutMs = Math.max(1, tool.timeoutMs());
    long startNanos = System.nanoTime();
    String traceId = TraceContext.get();

    Future<String> future;
    try {
      future = workers.submit(() -> {
        String prev = TraceContext.bind(traceId);
        try {
          return tool.execute(args);
        } finally {
          TraceContext.clear(prev);
        }
      });
    } catch (RejectedExecutionException busy) {
      auditSafely(() -> auditService.failed(username, ACTION_RESULT, TARGET_TYPE, name,
          "工具执行队列已满，本次调用未执行。参数=" + summary, "503"));
      return "【工具繁忙】服务端工具执行队列已满，工具 " + name + " 本次未执行，请稍后重试。";
    }

    try {
      String result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
      if (result == null) {
        auditSafely(() -> auditService.failed(username, ACTION_RESULT, TARGET_TYPE, name,
            "工具返回 null（应返回说明性文本）。耗时=" + elapsedMs(startNanos) + "ms", "500"));
        return "【工具空结果】" + name + " 未返回任何内容。";
      }
      auditSafely(() -> auditService.log(username, ACTION_RESULT, TARGET_TYPE, name,
          "OK，耗时=" + elapsedMs(startNanos) + "ms，输出 " + result.length() + " 字符（不记录正文）。"));
      return result;
    } catch (TimeoutException ex) {
      future.cancel(true);
      auditSafely(() -> auditService.failed(username, ACTION_RESULT, TARGET_TYPE, name,
          "工具超时：超过 " + timeoutMs + "ms 未返回，已中断。参数=" + summary, "504"));
      log.warn("工具 {} 超时（{}ms），已中断", name, timeoutMs);
      return "【工具超时】" + name + " 在 " + (timeoutMs / 1000) + " 秒内未返回，已中断并跳过。";
    } catch (ExecutionException ex) {
      Throwable cause = ex.getCause() == null ? ex : ex.getCause();
      String reason = cause.getClass().getSimpleName() + ": " + truncate(cause.getMessage(), FAILURE_DETAIL_LIMIT);
      auditSafely(() -> auditService.failed(username, ACTION_RESULT, TARGET_TYPE, name,
          "工具异常：" + reason + "。参数=" + summary, "500"));
      log.warn("工具 {} 执行异常：{}", name, reason);
      return "【工具失败】" + name + " 执行出错（" + reason + "），请改用其他方式或告知用户该工具暂不可用。";
    } catch (InterruptedException ex) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      auditSafely(() -> auditService.failed(username, ACTION_RESULT, TARGET_TYPE, name,
          "等待工具结果时被中断。参数=" + summary, "499"));
      return "【工具中断】等待 " + name + " 结果时被中断，已跳过。";
    }
  }

  /** 关停执行器（随 AgentService 的 {@code @PreDestroy} 一起调用）。 */
  public void shutdown() {
    workers.shutdownNow();
  }

  /**
   * 审计失败只记日志、不向上抛：审计库写不进去时对话仍应继续，
   * 但必须在服务端日志留下痕迹，避免「静默丢审计」。
   */
  private void auditSafely(Runnable write) {
    try {
      write.run();
    } catch (Exception ex) {
      log.error("AI 工具审计写入失败：{}", ex.getMessage());
    }
  }

  /** 参数摘要：压成单行并截断，避免审计表被超长 JSON 撑爆或出现换行噪音。 */
  private static String summarizeArgs(String rawArgs) {
    if (rawArgs == null || rawArgs.isBlank()) {
      return "{}";
    }
    String oneLine = rawArgs.replace('\r', ' ').replace('\n', ' ').trim();
    return truncate(oneLine, SUMMARY_LIMIT);
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return "";
    }
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
