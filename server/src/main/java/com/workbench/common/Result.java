package com.workbench.common;

import com.workbench.security.TraceContext;
import lombok.Getter;

/**
 * 统一接口返回结构。
 *
 * <p>{@code code / message / data} 是既有契约，前端按此解析，<b>不得改动顺序与语义</b>。
 * 治理底座 P1 追加 {@code traceId}：它与日志的 {@code traceId=}、审计表
 * {@code audit_log.trace_id}、响应头 {@code X-Request-Id} 同值，用于前端报错弹窗
 * 一键回带「哪一次请求」，也方便按截图直接 grep 后端日志。</p>
 *
 * <p>为兼容既有调用，仍需保留三参构造（{@code new Result<>(code, message, data)}），
 * 故这里手写构造器而非依赖 {@code @AllArgsConstructor}；{@code traceId} 由静态工厂
 * 在构造时自动从 {@link TraceContext#get()} 填充，调用方无需感知。</p>
 */
@Getter
public class Result<T> {

  private final String code;
  private final String message;
  private final T data;
  /** 本次请求的链路 id，与日志/审计/响应头同值；无上下文时取 {@link TraceContext#EMPTY}。 */
  private final String traceId;

  public Result(String code, String message, T data) {
    this(code, message, data, TraceContext.get());
  }

  public Result(String code, String message, T data, String traceId) {
    this.code = code;
    this.message = message;
    this.data = data;
    this.traceId = traceId == null || traceId.isBlank() ? TraceContext.EMPTY : traceId;
  }

  public static <T> Result<T> success(T data) {
    return new Result<>("0", "成功", data);
  }

  public static <T> Result<T> success() {
    return new Result<>("0", "成功", null);
  }

  public static <T> Result<T> fail(String code, String message) {
    return new Result<>(code, message, null);
  }
}