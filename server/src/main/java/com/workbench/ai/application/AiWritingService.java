package com.workbench.ai.application;

import com.workbench.ai.dto.WritingRequest;
import com.workbench.audit.application.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import com.workbench.ai.RestTemplateProxySupport;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AiWritingService {

  private static final int MAX_CONTENT_LENGTH = 30_000;
  private static final int MAX_PROMPT_LENGTH = 8_000;
  private static final long STREAM_TIMEOUT_MS = 120_000;

  private final Environment environment;
  private final AuditService auditService;
  private final AiConfigService configService;
  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * 流式生成线程池。每个进行中的生成请求会独占一个线程直到流结束（最长 STREAM_TIMEOUT_MS），
   * 因此必须用有界池：核心与最大线程数固定，队列也有界，超出即拒绝（见 rejectBusy），
   * 而不是像 newCachedThreadPool 那样在请求洪峰下无上限地创建线程拖垮整个后端。
   */
  private static final int STREAM_POOL_SIZE = 8;
  private static final int STREAM_QUEUE_CAPACITY = 16;

  private final ExecutorService executor = new ThreadPoolExecutor(
      STREAM_POOL_SIZE,
      STREAM_POOL_SIZE,
      0L,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(STREAM_QUEUE_CAPACITY),
      new ThreadPoolExecutor.AbortPolicy());

  @Autowired
  public AiWritingService(Environment environment, AuditService auditService, AiConfigService configService) {
    this(environment, auditService, configService, createRestTemplate());
  }

  AiWritingService(Environment environment, AuditService auditService,
      AiConfigService configService, RestTemplate restTemplate) {
    this.environment = environment;
    this.auditService = auditService;
    this.configService = configService;
    this.restTemplate = restTemplate;
  }

  @PreDestroy
  public void destroy() {
    executor.shutdownNow();
  }

  /**
   * 流式生成：后端代理 OpenAI 兼容 Chat Completions（stream=true），把模型返回的 SSE 逐帧
   * 原样转发给浏览器。密钥只在服务端（页面配置存库、或环境变量兜底），永不进入浏览器。这样
   * 也兼容任意实现了该接口格式的服务（OpenAI / DeepSeek / 通义 / 本地 Ollama 等）。
   */
  public SseEmitter streamGenerate(WritingRequest request) {
    // 同步校验：在返回 SseEmitter 之前完成，配置/参数错误以 JSON 错误响应返回，而非 SSE 流。
    String skillPrompt = trim(request == null ? null : request.skillPrompt());
    String content = trim(request == null ? null : request.content());
    String skillName = trim(request == null ? null : request.skillName());
    String additionalRequest = trim(request == null ? null : request.additionalRequest());

    if (skillPrompt.isBlank()) {
      throw new IllegalArgumentException("请先选择或填写一个 Skill");
    }
    if (content.isBlank()) {
      throw new IllegalArgumentException("请先填写原文或写作要点");
    }
    if (skillPrompt.length() > MAX_PROMPT_LENGTH) {
      throw new IllegalArgumentException("Skill 提示词不能超过 8000 个字符");
    }
    if (content.length() > MAX_CONTENT_LENGTH) {
      throw new IllegalArgumentException("输入内容不能超过 30000 个字符");
    }

    // 配置来源优先级：数据库里的页面配置 > 环境变量（见 AiConfigService）
    AiConfigService.AiSettings settings = configService.resolve();
    String baseUrl = settings.baseUrl();
    String model = settings.model();
    String apiKey = settings.apiKey();
    if (baseUrl == null || model == null) {
      throw new IllegalStateException(
          "AI 写作服务尚未配置，请在「AI 写作台 - 模型设置」里填写接口地址与模型（仅管理员可改）");
    }

    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
    try {
      executor.execute(() -> {
        try {
          String systemPrompt = "你是一名专业、严谨的中文写作助手。严格遵循用户提供的 Skill 指令；"
              + "不得编造输入中没有的事实、数据、政策或引用；信息不足时保留待补充内容。\n\n" + skillPrompt;
          String userPrompt = content;
          if (!additionalRequest.isBlank()) {
            userPrompt += "\n\n【补充要求】\n" + additionalRequest;
          }

          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("model", model);
          payload.put("messages", List.of(
              Map.of("role", "system", "content", systemPrompt),
              Map.of("role", "user", "content", userPrompt)));
          payload.put("temperature", 0.7);
          payload.put("stream", true);

          HttpHeaders headers = new HttpHeaders();
          headers.setContentType(MediaType.APPLICATION_JSON);
          if (!apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
          }
          String bodyJson = objectMapper.writeValueAsString(payload);

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
                    // 直接转发模型返回的 OpenAI 兼容 SSE data 帧（原样），前端按 OpenAI 格式解析
                    emitter.send(SseEmitter.event().data(data));
                  }
                }
                return null;
              });

          auditService.log(
              "AI_WRITE",
              "AI_WRITING",
              skillName.isBlank() ? "自定义 Skill" : skillName,
              "使用 AI 写作助手生成内容（流式）；未记录输入正文。");
          emitter.complete();
        } catch (Exception ex) {
          // 失败原因顺着 SSE 流回传，而不是 completeWithError：后者会让容器把请求当成异步错误，
          // 客户端最终只看到异步派发阶段的一个 403，真实原因（key 无效 / 上游超时）全被吞掉。
          try {
            emitter.send(SseEmitter.event().data(errorFrame(ex.getMessage())));
          } catch (Exception ignored) {
            // emitter 已关闭（如前端已断开）或编码失败，交给下面的 complete 收尾
          }
          try {
            emitter.complete();
          } catch (IllegalStateException ignored) {
            // emitter 已关闭，忽略
          }
        }
      });
    } catch (RejectedExecutionException busy) {
      // 有界线程池已满：与其让请求无限排队把内存和 SSE 超时一起拖爆，不如立刻回一个明确的忙提示帧
      try {
        emitter.send(SseEmitter.event().data(errorFrame("当前生成请求较多，请稍等几秒后重试")));
        emitter.complete();
      } catch (Exception ignored) {
        // emitter 已关闭（如前端已断开），忽略
      }
    }
    return emitter;
  }

  /**
   * 把失败原因编成一条 SSE data 帧，形如 data: {"error":"AI 服务请求失败（HTTP 401）：…"}。
   * 用 JSON 编码是为了保证 data 始终是单行（原始异常消息里可能有换行，会截断 SSE 帧）。
   */
  String errorFrame(String message) {
    String reason = (message == null || message.isBlank()) ? "AI 生成失败，请稍后重试" : message;
    try {
      // 复用实例级共享 ObjectMapper：错误路径不需要新实例，序列化器线程安全
      return objectMapper.writeValueAsString(Map.of("error", truncate(reason, 500)));
    } catch (Exception e) {
      return "{\"error\":\"AI 生成失败，请稍后重试\"}";
    }
  }

  private static String parseSseData(String line) {
    if (line == null) return null;
    String trimmed = line.trim();
    if (!trimmed.startsWith("data:")) return null;
    return trimmed.substring("data:".length()).trim();
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
    String normalized = baseUrl.replaceAll("/+$", "");
    return normalized.endsWith("/chat/completions")
        ? normalized
        : normalized + "/chat/completions";
  }
  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static RestTemplate createRestTemplate() {
    // 读超时要留足余量：慢模型出首个 token 前可能静默几十秒，120s 与 SSE 总超时相同会把
    // 「刚好卡在边界」的生成截断。放宽到 180s；整体仍受 SseEmitter(STREAM_TIMEOUT_MS) 兜底。
    return RestTemplateProxySupport.create(5_000, 180_000);
  }
}
