package com.workbench.security;

import org.slf4j.MDC;

/**
 * 全链路跟踪的链路标识工具（治理底座 P0）。
 *
 * <p>统一管理「链路 id」的取值、生成与清理，全仓只允许通过本类接触该值，避免
 * 各处各自起名（traceId / requestId / correlationId）导致对不上。</p>
 *
 * <p><b>线程模型说明</b>：本系统链路 id 放在 {@link MDC}（SLF4J，logback 同步 appender 生效），
 * 而不是塞进 {@code SecurityContext} 的 {@code details}——后者被
 * {@code CurrentUserHolder} 的 {@code instanceof Claims} 契约锁死（见
 * {@code JwtAuthFilterTest}），改动会破坏现有行为。SSE 用了手建的线程池（
 * {@code com.workbench.ai.application.AgentService} / {@code AiWritingService}），
 * Spring 的 {@code TaskDecorator} 够不着，因此这两处必须在提交时用闭包捕获
 * {@link #get()} 并手动透传（与 SecurityContext 不随线程池传递是同一套路）。</p>
 */
public final class TraceContext {

  /** MDC key，也是响应头 {@code X-Request-Id} 的取值来源。 */
  public static final String MDC_KEY = "traceId";

  /** 缺省值：无 trace 时的兜底，保证日志 pattern 永远有值、可 grep。 */
  public static final String EMPTY = "-";

  private static final String HEX = "0123456789abcdef";
  private static final java.util.concurrent.atomic.AtomicLong SEQ =
      new java.util.concurrent.atomic.AtomicLong();

  private TraceContext() {
  }

  /** 当前请求的链路 id；无 MDC 上下文时返回 {@value #EMPTY}，保证永不返回 null。 */
  public static String get() {
    String v = MDC.get(MDC_KEY);
    return (v == null || v.isBlank()) ? EMPTY : v;
  }

  /**
   * 生成新的链路 id：时间戳(毫秒) + 自增 6 位十六进制，保证同进程内唯一、可排序。
   *
   * <p>供 {@link TraceIdFilter} 在进入时调用；业务代码不需要主动生成。</p>
   */
  public static String generate() {
    return Long.toHexString(System.currentTimeMillis()) + "-" + nextSeq();
  }

  /**
   * 手动把指定链路 id 绑定到<b>当前线程</b>的 MDC，返回进入前的值。
   *
   * <p>供手建线程池的闭包透传：进入闭包时 {@code String prev = TraceContext.bind(traceId);}，
   * 结束（含异常路径）时在 finally 里 {@code TraceContext.clear(prev);}，否则 8 线程池
   * 会串上上一个请求的 traceId。空值按 {@value #EMPTY} 绑定，保证日志永不缺失。</p>
   */
  public static String bind(String traceId) {
    String prev = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, (traceId == null || traceId.isBlank()) ? EMPTY : traceId);
    return prev;
  }

  /**
   * 还原当前线程的链路 id 到 {@code prev}（由 {@link #bind(String)} 返回）。
   *
   * <p>{@code prev} 为 null 表示进入线程时没有值，应移除；否则恢复原值。</p>
   */
  public static void clear(String prev) {
    if (prev == null) {
      MDC.remove(MDC_KEY);
    } else {
      MDC.put(MDC_KEY, prev);
    }
  }

  private static String nextSeq() {
    long n = SEQ.incrementAndGet() & 0xffffffL;
    StringBuilder sb = new StringBuilder(6);
    for (int i = 5; i >= 0; i--) {
      sb.append(HEX.charAt((int) ((n >>> (i * 4)) & 0xf)));
    }
    return sb.toString();
  }
}