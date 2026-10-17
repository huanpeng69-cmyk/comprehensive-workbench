package com.workbench.ai.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workbench.ai.application.tool.ToolRegistry;
import com.workbench.ai.dto.AgentMessage;
import com.workbench.ai.dto.AgentRequest;
import com.workbench.audit.application.AuditService;
import com.workbench.memory.application.MemoryService;
import com.workbench.memory.dto.MemoryRecallResult;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

/**
 * 记忆注入接缝回归：拼接位置、检索话题、失败降级。
 *
 * <p>本测试锁住的是 {@link AgentService} 里的那段注入辅助逻辑——它没有独立的 Spring
 * 上下文可跑，却恰恰是最容易写错也最不该出错的地方：记忆只能拼在 system 之后（不能混进
 * user 历史），检索话题必须取「最后一条用户消息」，且检索任何异常都只能降级为「本轮不注入」，
 * 不能把整轮对话带崩。</p>
 */
class AgentServiceMemoryInjectionTest {

  private static final String BASE_PROMPT = "你是一个严谨的助手";
  private static final String MEMORY_BLOCK = "【用户长期记忆】\n- 报表金额统一用万元";

  private final AiConfigService configService = mock(AiConfigService.class);
  private final AuditService auditService = mock(AuditService.class);
  private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
  private final MemoryService memoryService = mock(MemoryService.class);
  private final RestTemplate restTemplate = mock(RestTemplate.class);
  private final AgentService service =
      new AgentService(configService, auditService, toolRegistry, memoryService, restTemplate);

  @AfterEach
  void tearDown() {
    service.destroy();
  }

  private static AgentRequest request(String systemPrompt, AgentMessage... messages) {
    return new AgentRequest(systemPrompt, List.of(messages), List.of(), true);
  }

  /** 用一条普通用户提问走一次注入，返回最终系统提示词。 */
  private String injected(String systemPrompt) {
    return service.withMemoryBlock(
        systemPrompt, request("ignored", new AgentMessage("user", "帮我把这张报表改成万元口径")));
  }

  @Test
  void memoryBlockIsAppendedAfterSystemPrompt() {
    when(memoryService.recallForPrompt(anyString()))
        .thenReturn(new MemoryRecallResult(MEMORY_BLOCK, List.of(), 18, 0));

    assertEquals(BASE_PROMPT + "\n\n" + MEMORY_BLOCK, injected(BASE_PROMPT));
  }

  @Test
  void recallQueryIsTheLastUserMessage() {
    when(memoryService.recallForPrompt(anyString())).thenReturn(MemoryRecallResult.empty());

    service.withMemoryBlock(
        BASE_PROMPT,
        request(
            "ignored",
            new AgentMessage("user", "第一条问题"),
            new AgentMessage("assistant", "第一条回答"),
            new AgentMessage("user", "第二条问题")));

    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    verify(memoryService).recallForPrompt(query.capture());
    assertEquals("第二条问题", query.getValue());
  }

  @Test
  void recallQueryIsBlankWhenOnlyAssistantMessagesExist() {
    when(memoryService.recallForPrompt(anyString())).thenReturn(MemoryRecallResult.empty());

    service.withMemoryBlock(BASE_PROMPT, request("ignored", new AgentMessage("assistant", "开场白")));

    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    verify(memoryService).recallForPrompt(query.capture());
    assertEquals("", query.getValue());
  }

  @Test
  void blankBlockLeavesSystemPromptUnchanged() {
    when(memoryService.recallForPrompt(anyString()))
        .thenReturn(new MemoryRecallResult("   ", List.of(), 0, 0));

    assertEquals(BASE_PROMPT, injected(BASE_PROMPT));
  }

  @Test
  void nullRecallResultLeavesSystemPromptUnchanged() {
    when(memoryService.recallForPrompt(anyString())).thenReturn(null);

    assertEquals(BASE_PROMPT, injected(BASE_PROMPT));
  }

  @Test
  void memoryLookupFailureDegradesToNoInjection() {
    when(memoryService.recallForPrompt(anyString())).thenThrow(new RuntimeException("memory db down"));

    assertEquals(BASE_PROMPT, injected(BASE_PROMPT));
  }

  @Test
  void lastUserMessageSkipsAssistantAndBlankUserMessages() {
    assertEquals(
        "第二条问题",
        AgentService.lastUserMessage(
            request(
                "ignored",
                new AgentMessage("user", "第一条问题"),
                new AgentMessage("assistant", "回答"),
                new AgentMessage("user", "   "),
                new AgentMessage("user", "第二条问题"))));
  }

  @Test
  void lastUserMessageIsEmptyWhenThereIsNoHistory() {
    assertEquals("", AgentService.lastUserMessage(null));
    assertEquals("", AgentService.lastUserMessage(new AgentRequest(null, null, List.of(), true)));
    assertEquals(
        "",
        AgentService.lastUserMessage(request("ignored", new AgentMessage("assistant", "只有回答"))));
  }

  @Test
  void streamAgentStillStartsWhenMemoryLookupThrows() {
    when(configService.resolve())
        .thenReturn(new AiConfigService.AiSettings("http://127.0.0.1:9/v1", "test-model", "k", "env"));
    when(toolRegistry.openAiToolSpecs(any())).thenReturn(List.of());
    when(memoryService.recallForPrompt(anyString())).thenThrow(new RuntimeException("memory db down"));

    // 关键契约：记忆检索异常发生在主线程的注入步骤里，必须被吞掉——
    // 一旦漏出去，用户看到的就是「记忆坏了，连对话都开不了」。
    Object emitter =
        assertDoesNotThrow(
            () -> service.streamAgent(request("ignored", new AgentMessage("user", "问题"))));

    assertNotNull(emitter);
  }
}
