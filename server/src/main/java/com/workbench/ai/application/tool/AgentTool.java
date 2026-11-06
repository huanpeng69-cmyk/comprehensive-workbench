package com.workbench.ai.application.tool;

import java.util.Map;
import java.util.Set;

/**
 * Agent 可调用的工具抽象。每个工具描述自己是什么、参数长什么样、怎么执行。
 * 返回的字符串就是喂回模型的「工具结果」——任何异常都应降级成说明性文本，绝不能抛出，
 * 否则会打断整个 Agent 循环。该契约由工具自觉遵守；越界、卡死、超时等「不合约」情况
 * 由 {@link ToolInvoker} 兜底（隔离执行 + 超时中断 + 降级文案），编排层不直接调用本接口。
 */
public interface AgentTool {

  /** 工具名（与 LLM function calling 的 name 对齐）。 */
  String name();

  /** 给模型看的工具用途说明。 */
  String description();

  /** OpenAI function calling 的 parameters JSON Schema 对象（不含外层 type=function 包裹）。 */
  Map<String, Object> parametersSchema();

  /** 执行工具，返回给模型的文本结果。 */
  String execute(Map<String, Object> args);

  // ---- 治理元信息：默认值取「可用且最保守」的姿态，工具按需覆盖 ----

  /**
   * 允许调用本工具的角色码（去掉 {@code ROLE_} 前缀，如 {@code HR}、{@code ADMIN}）。
   *
   * <p>空集合表示不限制角色。默认不限制是为了不改动既有工具的行为；新增的敏感工具应显式声明，
   * 由 {@link ToolInvoker} 在调用前比对当前用户角色，不足则拒绝并留 {@code DENIED} 审计。</p>
   */
  default Set<String> requiredRoles() {
    return Set.of();
  }

  /** 是否只读工具（只查不改）。供审计与后续「写操作二次确认」判定使用。 */
  default boolean readOnly() {
    return true;
  }

  /**
   * 是否要求「项目部」数据范围。
   *
   * <p>{@code true}（默认）表示工具只能在该用户的<b>项目上下文</b>内执行：调用者既没有
   * {@code projectId}、又不具备全公司数据范围（{@code data_scope=COMPANY}）时，
   * {@link ToolInvoker} 直接拒绝并留 {@code DENIED} 审计，不进入工具实现。
   * COMPANY 级角色合法地没有 {@code projectId}，因此判定用「全公司范围 OR 已绑定项目部」，
   * 而不是「projectId 非空」——硬判会误拒公司级账号。
   *
   * <p>默认 {@code true} 是保守取值：新工具不声明即按「需要项目范围」处理，
   * 写错方向的代价是拒绝而不是越权。确实与项目部无关的工具（如外部知识库检索）必须显式声明
   * {@code false}，并在其 javadoc 里说明理由。
   */
  default boolean needsProjectScope() {
    return true;
  }

  /** 单次调用最长等待毫秒数；超时由 {@link ToolInvoker} 中断并按「工具超时」降级。 */
  default int timeoutMs() {
    return 15_000;
  }
}
