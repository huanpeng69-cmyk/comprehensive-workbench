package com.workbench.ai.application;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.workbench.ai.dto.WritingRequest;
import com.workbench.audit.application.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class AiWritingServiceTest {

  @Test
  void streamsOpenAiCompatibleSseFramesToTheEmitter() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("workbench.ai.base-url", "https://ai.example/v1/")
        .withProperty("workbench.ai.model", "writer-model")
        .withProperty("workbench.ai.api-key", "test-secret");
    AuditService auditService = mock(AuditService.class);
    RestTemplate restTemplate = new RestTemplate();
    // 配置改由 AiConfigService 解析（库配置优先，环境变量兜底），这里模拟库里已存好的配置
    AiConfigService configService = mock(AiConfigService.class);
    when(configService.resolve()).thenReturn(new AiConfigService.AiSettings(
        "https://ai.example/v1/", "writer-model", "test-secret", AiConfigService.SOURCE_DB));
    AiWritingService service =
        new AiWritingService(environment, auditService, configService, restTemplate);
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

    String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"整\"}}]}\n\n"
        + "data: {\"choices\":[{\"delta\":{\"content\":\"理\"}}]}\n\n"
        + "data: [DONE]\n\n";
    server.expect(requestTo("https://ai.example/v1/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-secret"))
        .andExpect(jsonPath("$.model").value("writer-model"))
        .andExpect(jsonPath("$.stream").value(true))
        .andExpect(jsonPath("$.messages[0].content")
            .value(containsString("润色")))
        .andExpect(jsonPath("$.messages[1].content")
            .value("待润色的文字\n\n【补充要求】\n语气正式"))
        .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));

    service.streamGenerate(new WritingRequest(
        "公文润色", "润色并保持原意", "待润色的文字", "语气正式"));

    // 流式转发在后台线程执行；审计落库即代表整条链路（请求构造 + SSE 转发 + complete）成功
    verify(auditService, timeout(3000)).log("AI_WRITE", "AI_WRITING", "公文润色",
        "使用 AI 写作助手生成内容（流式）；未记录输入正文。");
    server.verify();
  }

  @Test
  void encodesFailureReasonIntoASingleLineSseFrame() {
    AiWritingService service = new AiWritingService(
        new MockEnvironment(), mock(AuditService.class),
        mock(AiConfigService.class), new RestTemplate());
    String frame = service.errorFrame("AI 服务请求失败（HTTP 401）：Invalid API key");
    assertEquals("{\"error\":\"AI 服务请求失败（HTTP 401）：Invalid API key\"}", frame);
    // data 行必须单行，否则 SSE 帧会被截断（异常消息里带了换行）
    assertEquals(1, frame.split("\n", -1).length);
    // null/空白不能编出空 cause
    assertEquals("{\"error\":\"AI 生成失败，请稍后重试\"}", service.errorFrame(null));
  }

  @Test
  void rejectsMissingProviderConfigurationBeforeOpeningStream() {
    AiConfigService configService = mock(AiConfigService.class);
    when(configService.resolve()).thenReturn(
        new AiConfigService.AiSettings(null, null, null, AiConfigService.SOURCE_ENV));
    AiWritingService service =
        new AiWritingService(new MockEnvironment(), mock(AuditService.class), configService);

    assertThrows(IllegalStateException.class, () -> service.streamGenerate(
        new WritingRequest("公文润色", "润色", "正文", "")));
  }

  @Test
  void rejectsBlankContent() {
    AiConfigService configService = mock(AiConfigService.class);
    when(configService.resolve()).thenReturn(
        new AiConfigService.AiSettings(null, null, null, AiConfigService.SOURCE_ENV));
    AiWritingService service =
        new AiWritingService(new MockEnvironment(), mock(AuditService.class), configService);

    assertThrows(IllegalArgumentException.class, () -> service.streamGenerate(
        new WritingRequest("公文润色", "润色", "   ", "")));
  }
}
