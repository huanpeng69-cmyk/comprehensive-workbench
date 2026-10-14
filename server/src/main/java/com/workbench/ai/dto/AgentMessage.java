package com.workbench.ai.dto;

/**
 * 单条对话消息。客户端只传文本角色（system / user / assistant），tool 角色由服务端在
 * Agent 循环内部生成，不会出现在请求里。
 */
public record AgentMessage(String role, String content) {
}
