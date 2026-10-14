package com.workbench.ai.dto;

import java.util.List;

/**
 * 通用 Agent 请求：客户端把完整对话历史 + 可配置的系统提示词（人设）发过来，服务端无状态编排。
 *
 * @param systemPrompt 可选的人设 / 系统提示词；为空时回落到内置默认通用助手。
 * @param messages     完整对话历史（system 角色可省略，由 systemPrompt 单独承担）。
 * @param tools        启用哪些工具（按名称）；null = 启用所有已注册工具（兼容旧客户端）；
 *                     空列表 = 不启用任何工具，智能体直连模型作答（跳过知识库等工具）。
 * @param stream       是否流式（前端固定传 true）。
 */
public record AgentRequest(String systemPrompt, List<AgentMessage> messages, List<String> tools, Boolean stream) {
}
