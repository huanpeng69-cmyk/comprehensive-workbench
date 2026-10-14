package com.workbench.ai.application;

import com.workbench.ai.application.tool.AgentTool;
import com.workbench.ai.application.tool.ToolRegistry;
import com.workbench.ai.dto.AgentMessage;
import com.workbench.ai.dto.AgentRequest;
import com.workbench.audit.application.AuditService;
import com.workbench.security.CurrentUserHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import com.workbench.ai.RestTemplateProxySupport;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 通用 Agent 编排。
 *
 * <p>流程：把系统提示词 + 对话历史拼成 OpenAI 兼容 messages，循环调用 Chat Completions——
 * 若模型返回 tool_calls，就执行工具、把结果回灌、再问模型（最多 {@link #MAX_AGENT_ITERATIONS}
 * 轮防失控）；若返回纯文本则流式输出最终答案。所有事件通过自定义 SSE 协议推给前端
 * （message / tool_call / tool_result / done / error）。
 *
 * <p>与 AiWritingService 的区别：写作台是「单轮一次性生成」；这里是「多轮 + 可调用工具的 Agent」。
 * 配置仍复用 AiConfigService.resolve()，密钥只在服务端。
 */
@Service
public class AgentService {

  /** 单条内容 / 提示词上限，防滥用与超长上下文。 */
  private static final int MAX_CONTENT_LENGTH = 30_000;
  private static final int MAX_PROMPT_LENGTH = 8_000;
  private static final long STREAM_TIMEOUT_MS = 180_000;
  /** Agent 最多循环轮次：每轮最多可能触发一次工具调用。 */
  private static final int MAX_AGENT_ITERATIONS = 6;

  private static final String DEFAULT_SYSTEM_PROMPT =
      "你是一个通用的智能助手，善于理解用户意图，并在需要时调用可用的工具获取信息，"
          + "再给出准确、条理清晰、用中文表达的回答。不要编造不确定的事实；信息不足时坦诚说明。";

  private final AiConfigService configService;
  private final AuditService auditService;
  private final ToolRegistry toolRegistry;
  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * 流式生成线程池：每个 Agent 请求独占一个线程直到流结束，必须有界（见 AiWritingService 注释）。
   */
  private final ExecutorService executor = new ThreadPoolExecutor(
      8, 8, 0L, TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());

  @Autowired
  public AgentService(
      AiConfigService configService, AuditService auditService, ToolRegistry toolRegistry) {
    this(configService, auditService, toolRegistry, createRestTemplate());
  }

  /** 包级构造器供测试注入打桩过的 RestTemplate。 */
  AgentService(
      AiConfigService configService, AuditService auditService,
      ToolRegistry toolRegistry, RestTemplate restTemplate) {
    this.configService = configService;
    this.auditService = auditService;
    this.toolRegistry = toolRegistry;
    this.restTemplate = restTemplate;
  }

  @PreDestroy
  public void destroy() {
    executor.shutdownNow();
  }

  public SseEmitter streamAgent(AgentRequest request) {
    // 同步校验，在返回 SseEmitter 之前完成，错误以 JSON 错误帧回传。
    if (request == null || request.messages() == null || request.messages().isEmpty()) {
      throw new IllegalArgumentException("messages 不能为空");
    }
    AiConfigService.AiSettings settings = configService.resolve();
    String baseUrl = settings.baseUrl();
    String model = settings.model();
    String apiKey = settings.apiKey();
    if (baseUrl == null || model == null) {
      throw new IllegalStateException(
          "AI 服务尚未配置，请在「模型设置」里填写接口地址与模型（仅管理员可改）");
    }
    String systemPrompt = request.systemPrompt() == null ? DEFAULT_SYSTEM_PROMPT : request.systemPrompt().trim();
    if (systemPrompt.length() > MAX_PROMPT_LENGTH) {
      throw new IllegalArgumentException("系统提示词不能超过 8000 个字符");
    }

    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
    // 安全上下文不会自动传到线程池，在主线程先取出用户名再传给 Agent 线程。
    String username = CurrentUserHolder.currentUsername();
    try {
      executor.execute(
          () -> runAgent(request, systemPrompt, baseUrl, model, apiKey, username, emitter));
    } catch (RejectedExecutionException busy) {
      try {
        emitter.send(SseEmitter.event().data(errorFrame("当前请求较多，请稍等几秒后重试")));
        emitter.complete();
      } catch (Exception ignored) {
        // emitter 已关闭（如前端已断开），忽略
      }
    }
    return emitter;
  }

  private void runAgent(
      AgentRequest request, String systemPrompt, String baseUrl, String model,
      String apiKey, String username, SseEmitter emitter) {
    try {
      // 基础消息：系统提示词 + 客户端发来的历史（文本角色）。
      List<Map<String, Object>> messages = new ArrayList<>();
      messages.add(Map.of("role", "system", "content", systemPrompt));
      for (AgentMessage m : request.messages()) {
        String role = m.role() == null ? "user" : m.role();
        String content = m.content() == null ? "" : m.content();
        if (content.length() > MAX_CONTENT_LENGTH) {
          content = content.substring(0, MAX_CONTENT_LENGTH) + "…";
        }
        messages.add(Map.of("role", role, "content", content));
      }

      List<Map<String, Object>> toolSpecs = toolRegistry.openAiToolSpecs(request.tools());

      boolean answered = false;
      // 直连模型模式：未启用任何工具时跳过「非流式决策」往返，直接流式作答——
      // 否则决策调用期间 UI 只能干等「思考中」，且多花一整轮 LLM 往返。
      if (!toolSpecs.isEmpty()) {
        for (int iter = 0; iter < MAX_AGENT_ITERATIONS; iter++) {
          // 1) 决策调用（非流式，带工具）：拿到 tool_calls 或纯文本。
          Map<String, Object> decide = chatNonStream(baseUrl, model, apiKey, messages, toolSpecs);
          Map<String, Object> assistantMsg = firstMessage(decide);
          List<Map<String, Object>> toolCalls = asToolCalls(assistantMsg.get("tool_calls"));

          if (toolCalls.isEmpty()) {
            // 2a) 纯文本：流式输出最终答案（tools 不传，避免又触发工具）。
            streamAnswer(baseUrl, model, apiKey, messages, emitter);
            answered = true;
            break;
          }

          // 2b) 有工具调用：把 assistant(tool_calls) 留在上下文，逐个执行并回灌。
          messages.add(assistantMsg);
          for (Map<String, Object> tc : toolCalls) {
            Map<String, Object> fn = asMap(tc.get("function"));
            String id = asString(tc.get("id"));
            String name = asString(fn.get("name"));
            String rawArgs = asString(fn.get("arguments"));
            Map<String, Object> args = parseArgs(rawArgs);

            if (!safeSend(emitter, event("tool_call", Map.of("id", id, "name", name, "args", rawArgs)))) {
              return;
            }
            AgentTool tool = toolRegistry.get(name);
            String result = (tool == null) ? "【未知工具】" + name : tool.execute(args);
            if (!safeSend(emitter, event("tool_result", Map.of("id", id, "content", truncate(result, 4000))))) {
              return;
            }
            Map<String, Object> toolMsg = new LinkedHashMap<>();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", id);
            toolMsg.put("content", result);
            messages.add(toolMsg);
          }
        }
      }

      // 达到最大轮次仍未产出最终文本：补一句流式收尾，避免前端一直转圈。
      if (!answered) {
        streamAnswer(baseUrl, model, apiKey, messages, emitter);
      }

      auditService.log(
          username,
          "AI_AGENT", "AI_AGENT",
          systemPrompt.length() > 40 ? systemPrompt.substring(0, 40) + "…" : systemPrompt,
          "使用通用 Agent 对话（可能含工具调用），未记录对话正文。");
      // 显式发送 done 帧：经 Node/Vite 代理时 HTTP 流关闭事件可能不透传，
      // 前端靠 done 帧结束「思考中」状态，不能只依赖连接关闭。
      safeSend(emitter, event("done", Map.of()));
      emitter.complete();
    } catch (Exception ex) {
      // 失败原因顺着 SSE 流回传，而不是 completeWithError（后者会被容器转成 403 吞掉真因）。
      try {
        emitter.send(SseEmitter.event().data(errorFrame(ex.getMessage())));
      } catch (Exception ignored) {
      }
      try {
        emitter.complete();
      } catch (IllegalStateException ignored) {
      }
    }
  }

  /** 非流式决策调用，返回完整响应 JSON（Map）。 */
  private Map<String, Object> chatNonStream(
      String baseUrl, String model, String apiKey,
      List<Map<String, Object>> messages, List<Map<String, Object>> toolSpecs) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", model);
    payload.put("messages", messages);
    if (toolSpecs != null && !toolSpecs.isEmpty()) {
      payload.put("tools", toolSpecs);
      payload.put("tool_choice", "auto");
    }
    payload.put("temperature", 0.7);
    payload.put("stream", false);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (apiKey != null && !apiKey.isBlank()) {
      headers.setBearerAuth(apiKey);
    }
    String bodyJson = writeJson(payload);
    try {
      return restTemplate.postForObject(completionUrl(baseUrl), new HttpEntity<>(bodyJson, headers), Map.class);
    } catch (HttpStatusCodeException e) {
      throw new IllegalStateException(
          "AI 服务请求失败（HTTP " + e.getStatusCode().value() + "）：" + truncate(e.getResponseBodyAsString(), 500));
    } catch (ResourceAccessException e) {
      throw new IllegalStateException(
          "AI 服务连接失败（当前出网：" + RestTemplateProxySupport.describeProxy()
              + "）：" + truncate(e.getMessage(), 200)
              + "。请确认该代理/网络能访问 " + baseUrl + "（可在本机命令行用 curl -x 你的代理 测一下）。");
    }
  }

  /** 流式输出最终答案：不带工具，逐帧把 delta.content 转成 message 事件。 */
  private void streamAnswer(
      String baseUrl, String model, String apiKey,
      List<Map<String, Object>> messages, SseEmitter emitter) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", model);
    payload.put("messages", messages);
    payload.put("temperature", 0.7);
    payload.put("stream", true);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (apiKey != null && !apiKey.isBlank()) {
      headers.setBearerAuth(apiKey);
    }
    String bodyJson = writeJson(payload);

    try {
      restTemplate.execute(
          completionUrl(baseUrl),
          HttpMethod.POST,
          req -> {
            req.getHeaders().putAll(headers);
            req.getBody().write(bodyJson.getBytes(StandardCharsets.UTF_8));
          },
          response -> {
            int status = response.getStatusCode().value();
            if (status != 200) {
              String errBody = readStream(response.getBody());
              throw new IllegalStateException(
                  "AI 服务请求失败（HTTP " + status + "）：" + truncate(errBody, 500));
            }
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
              String line;
              while ((line = reader.readLine()) != null) {
                String data = parseSseData(line);
                if (data == null || "[DONE]".equals(data)) {
                  continue;
                }
                String delta = parseContentDelta(data);
                if (delta != null && !delta.isEmpty()) {
                  if (!safeSend(emitter, event("message", Map.of("content", delta)))) {
                    return null;
                  }
                }
              }
            }
            return null;
          });
    } catch (IllegalStateException ex) {
      // 上面主动抛的 HTTP 错误；若前端已断开，safeSend 也会以异常形式到这里，统一处理。
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("AI 流式输出失败：" + truncate(ex.getMessage(), 300));
    }
  }

  /** 把事件对象编成 SSE data 帧，形如 data: {"type":"message","content":"…"}。 */
  private String event(String type, Map<String, Object> fields) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("type", type);
    frame.putAll(fields);
    return writeJson(frame);
  }

  /** 失败帧：{"error":"原因"}（单行 JSON，避免换行截断 SSE 帧）。 */
  private String errorFrame(String message) {
    String reason = (message == null || message.isBlank()) ? "Agent 执行失败，请稍后重试" : message;
    try {
      return objectMapper.writeValueAsString(Map.of("type", "error", "message", truncate(reason, 500)));
    } catch (Exception e) {
      return "{\"type\":\"error\",\"message\":\"Agent 执行失败，请稍后重试\"}";
    }
  }

  private boolean safeSend(SseEmitter emitter, String json) {
    try {
      emitter.send(SseEmitter.event().data(json));
      return true;
    } catch (Exception e) {
      // 客户端已断开（如关闭页面），停止推送。
      return false;
    }
  }

  private static Map<String, Object> firstMessage(Map<String, Object> decide) {
    if (decide == null) {
      throw new IllegalStateException("模型返回为空，请稍后重试");
    }
    Object choices = decide.get("choices");
    if (!(choices instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalStateException("模型返回异常（无 choices）：" + truncate(decide.toString(), 200));
    }
    return asMap(list.get(0));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asToolCalls(Object toolCalls) {
    if (toolCalls instanceof List<?> list) {
      List<Map<String, Object>> result = new ArrayList<>();
      for (Object o : list) {
        if (o instanceof Map<?, ?> m) {
          result.add((Map<String, Object>) m);
        }
      }
      return result;
    }
    return new ArrayList<>();
  }

  private Map<String, Object> parseArgs(String rawArgs) {
    if (rawArgs == null || rawArgs.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(rawArgs, Map.class);
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }

  private static String parseSseData(String line) {
    if (line == null) {
      return null;
    }
    String trimmed = line.trim();
    if (!trimmed.startsWith("data:")) {
      return null;
    }
    return trimmed.substring("data:".length()).trim();
  }

  /**
   * OpenAI 流式帧里取 choices[0].delta.content。
   *
   * <p>复用实例级共享 {@link ObjectMapper}：本方法在每个流式帧上都会被调用，
   * 原先每帧 new 一个实例纯属浪费（序列化器线程安全，无状态）。
   */
  private String parseContentDelta(String data) {
    try {
      Map<String, Object> node = objectMapper.readValue(data, Map.class);
      Object choices = node.get("choices");
      if (choices instanceof List<?> list && !list.isEmpty()) {
        Map<String, Object> choice = asMap(list.get(0));
        Map<String, Object> delta = asMap(choice.get("delta"));
        Object content = delta.get("content");
        return content instanceof String s ? s : null;
      }
    } catch (Exception e) {
      // 非 JSON 心跳帧，忽略
    }
    return null;
  }

  private static Map<String, Object> asMap(Object o) {
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (o instanceof Map<?, ?> map) ? (Map<String, Object>) map : new LinkedHashMap<>();
    return m;
  }

  private static String asString(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private String writeJson(Object o) {
    try {
      return objectMapper.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException("序列化请求体失败：" + truncate(e.getMessage(), 200));
    }
  }

  private static String readStream(InputStream in) {
    try {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "";
    }
  }

  private static String truncate(String s, int max) {
    return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
  }

  private static String completionUrl(String baseUrl) {
    String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed + "chat/completions" : trimmed + "/chat/completions";
  }

  private static RestTemplate createRestTemplate() {
    return RestTemplateProxySupport.create(10_000, 180_000);
  }
}
