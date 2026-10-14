package com.workbench.ai.application.tool;

import java.util.Map;

/**
 * Agent 可调用的工具抽象。每个工具描述自己是什么、参数长什么样、怎么执行。
 * 返回的字符串就是喂回模型的「工具结果」——任何异常都应降级成说明性文本，绝不能抛出，
 * 否则会打断整个 Agent 循环。
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
}
