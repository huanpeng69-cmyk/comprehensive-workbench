package com.workbench.ai.api;

import com.workbench.ai.application.AgentService;
import com.workbench.ai.application.AiWritingService;
import com.workbench.ai.dto.AgentRequest;
import com.workbench.ai.dto.WritingRequest;
import com.workbench.security.Permissions;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 写作台 / 通用 Agent：把 Skill 提示词与待处理内容交给后端代理的 OpenAI 兼容服务。
 * 采用流式（SSE）转发，密钥只存在于服务端，浏览器不接触。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code /generate}：旧的单轮「写作」入口（保留兼容）。</li>
 *   <li>{@code /agent}：新的通用 Agent——多轮对话 + 可配置人设 + 工具调用。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/writing")
public class WritingController {

  private final AiWritingService writingService;
  private final AgentService agentService;

  public WritingController(AiWritingService writingService, AgentService agentService) {
    this.writingService = writingService;
    this.agentService = agentService;
  }

  /** 旧入口：单轮一次性生成（兼容保留）。 */
  @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter generate(@RequestBody WritingRequest request) {
    return writingService.streamGenerate(request);
  }

  /**
   * 新入口：通用 Agent（多轮 + 人设 + 工具调用），自定义 SSE 事件流。
   *
   * <p>ADR-M013：入口加 {@link Permissions#AI_AGENT_USE} 权限点。Agent 会按登录身份调用工具，
   * 「只要能登录就能用 Agent」等于把工具交到所有账号手里；权限点落在 {@code sys_permission} 上，
   * 收回只需删一行 {@code sys_role_permission}，不必改代码再发版。无权限时方法体根本不执行，
   * 由 {@code GlobalExceptionHandler} 返回 403 信封（前端 {@code aiWriting.ts} 已按
   * {@code res.ok} + {@code message} 兜住，无需改动）。</p>
   */
  @PreAuthorize(Permissions.HAS_AI_AGENT_USE)
  @PostMapping(value = "/agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter agent(@RequestBody AgentRequest request) {
    return agentService.streamAgent(request);
  }
}
