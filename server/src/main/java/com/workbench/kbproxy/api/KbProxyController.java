package com.workbench.kbproxy.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbench.audit.application.AuditService;
import com.workbench.common.Result;
import com.workbench.kbproxy.dto.KbChatRequest;
import com.workbench.kbproxy.dto.KbQueryRequest;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 企业知识库（.pi 项目）桥接：工作台内「智能问答」后端转发。
 * 接口要求工作台 JWT（SecurityConfig 自动拦截）；服务端以只读服务令牌调用知识库
 * /api/chat、/api/query，令牌不进浏览器。知识库侧令牌为空时返回 401/403。
 *
 * <p>知识库没有流式端点（{@code /api/chat} 入参只有 question / history / top_k /
 * min_relevance），所以“流式”由工作台侧实现：拿到整段答案后用 SSE 逐帧推送，
 * 见 {@link #chatStream(KbChatRequest)}。首字延迟仍等于知识库整段生成时间。</p>
 */
@RestController
@RequestMapping("/api/kb")
public class KbProxyController {

  private static final Logger log = LoggerFactory.getLogger(KbProxyController.class);

  /** 流式推送线程数：只做内存切片与写帧，不占用长连接，4 个足够。 */
  private static final int STREAM_POOL_SIZE = 4;
  /** SSE 连接超时：知识库侧读超时 90s，留 30s 余量。 */
  private static final long STREAM_TIMEOUT_MS = 120_000L;
  /** 帧间隔 25ms ≈ 40 帧/秒：肉眼是连续增长，不是一块块跳。 */
  private static final long FRAME_INTERVAL_MS = 25L;
  /** 单帧最长字符数：太短帧数暴增，太长失去渐显效果。 */
  static final int MAX_PIECE_CHARS = 24;

  private final RestTemplate restTemplate;
  private final AuditService auditService;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String kbBaseUrl;
  private final String kbServiceToken;
  private final ExecutorService streamExecutor;

  public KbProxyController(
      AuditService auditService,
      @Value("${workbench.kb.base-url}") String kbBaseUrl,
      @Value("${workbench.kb.service-token}") String kbServiceToken) {
    this.auditService = auditService;
    this.kbBaseUrl = kbBaseUrl;
    this.kbServiceToken = kbServiceToken;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5000);
    factory.setReadTimeout(90000);
    this.restTemplate = new RestTemplate(factory);
    this.streamExecutor =
        Executors.newFixedThreadPool(
            STREAM_POOL_SIZE,
            runnable -> {
              Thread thread = new Thread(runnable, "kb-sse");
              thread.setDaemon(true);
              return thread;
            });
  }

  @PreDestroy
  public void destroy() {
    streamExecutor.shutdownNow();
  }

  @PostMapping("/chat")
  public Result<Map<String, Object>> chat(@RequestBody KbChatRequest req) {
    if (req.question() == null || req.question().isBlank()) {
      throw new IllegalArgumentException("question 不能为空");
    }
    Map<String, Object> data = forward("/api/chat", req);
    auditService.log("KB_CHAT", "KB_ASSISTANT", req.question(), "工作台内调用企业知识库智能客服");
    return Result.success(data);
  }

  @PostMapping("/query")
  public Result<Map<String, Object>> query(@RequestBody KbQueryRequest req) {
    if (req.question() == null || req.question().isBlank()) {
      throw new IllegalArgumentException("question 不能为空");
    }
    Map<String, Object> data = forward("/api/query", req);
    auditService.log("KB_QUERY", "KB_ASSISTANT", req.question(), "工作台内调用企业知识库检索");
    return Result.success(data);
  }

  /**
   * 流式问答（SSE）。事件体为 JSON 字符串，{@code type} 取值：
   *
   * <ul>
   *   <li>{@code meta}：引用、检索模式、图谱证据（正文之前先发，前端可先渲染来源）</li>
   *   <li>{@code delta}：答案片段，按顺序拼接即为完整答案</li>
   *   <li>{@code done}：正常结束</li>
   *   <li>{@code error}：失败原因，{@code message} 为可直接展示的中文</li>
   * </ul>
   *
   * <p>参数校验仍在主线程同步完成：这样用错参数拿到的是统一的 JSON 错误信封，
   * 而不是一个只带 error 帧的 200 流。</p>
   */
  @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter chatStream(@RequestBody KbChatRequest req) {
    if (req.question() == null || req.question().isBlank()) {
      throw new IllegalArgumentException("question 不能为空");
    }
    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
    // 客户端断开/超时后置位：剩下的帧不再推，避免对已关闭的 emitter 反复写。
    AtomicBoolean cancelled = new AtomicBoolean(false);
    emitter.onCompletion(() -> cancelled.set(true));
    emitter.onError(ex -> cancelled.set(true));
    emitter.onTimeout(() -> cancelled.set(true));
    try {
      streamExecutor.execute(() -> runChatStream(req, emitter, cancelled));
    } catch (RejectedExecutionException busy) {
      sendFrameQuietly(emitter, errorFrame("当前请求较多，请稍等几秒后重试"));
      emitter.complete();
    }
    return emitter;
  }

  /** 工作线程：取整段答案 → 发 meta → 逐帧发 delta → 发 done。 */
  private void runChatStream(KbChatRequest req, SseEmitter emitter, AtomicBoolean cancelled) {
    try {
      Map<String, Object> data = forward("/api/chat", req);
      auditService.log(
          "KB_CHAT", "KB_ASSISTANT", req.question(), "工作台内调用企业知识库智能客服（流式）");

      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("type", "meta");
      meta.put("citations", data.getOrDefault("citations", List.of()));
      meta.put("retrieval_mode", data.get("retrieval_mode"));
      meta.put("mode", data.get("mode"));
      meta.put("graph_evidence", data.getOrDefault("graph_evidence", List.of()));
      sendFrame(emitter, objectMapper.writeValueAsString(meta));

      Object rawAnswer = data.get("answer");
      String answer = rawAnswer == null ? "" : String.valueOf(rawAnswer);
      for (String piece : splitAnswer(answer)) {
        if (cancelled.get()) {
          break;
        }
        sendFrame(emitter, objectMapper.writeValueAsString(Map.of("type", "delta", "content", piece)));
        if (!sleepFrame()) {
          break;
        }
      }
      if (!cancelled.get()) {
        sendFrame(emitter, objectMapper.writeValueAsString(Map.of("type", "done")));
      }
      emitter.complete();
    } catch (Exception ex) {
      log.warn("知识库流式问答中断：{}", ex.getMessage());
      sendFrameQuietly(emitter, errorFrame(describeFailure(ex)));
      emitter.complete();
    }
  }

  /**
   * 把整段答案切成推送片段：优先在句末标点或换行后断开，最长
   * {@value #MAX_PIECE_CHARS} 个字符。只决定传输节奏，不增删任何字符——
   * 前端顺序拼回去与直接收整段完全一致。
   */
  static List<String> splitAnswer(String answer) {
    List<String> pieces = new ArrayList<>();
    if (answer == null || answer.isEmpty()) {
      return pieces;
    }
    int start = 0;
    for (int i = 0; i < answer.length(); i++) {
      char c = answer.charAt(i);
      boolean sentenceEnd = c == '。' || c == '！' || c == '？' || c == '；' || c == '\n';
      boolean tooLong = i - start + 1 >= MAX_PIECE_CHARS;
      if (sentenceEnd || tooLong) {
        pieces.add(answer.substring(start, i + 1));
        start = i + 1;
      }
    }
    if (start < answer.length()) {
      pieces.add(answer.substring(start));
    }
    return pieces;
  }

  /** 写一帧；客户端已断开时抛 IOException，由调用方决定吞掉还是上报。 */
  private void sendFrame(SseEmitter emitter, String json) throws java.io.IOException {
    emitter.send(SseEmitter.event().data(json));
  }

  private void sendFrameQuietly(SseEmitter emitter, String json) {
    try {
      sendFrame(emitter, json);
    } catch (Exception ignored) {
      // 前端已断开：错误帧发不出去属正常情况，不再二次抛出
    }
  }

  /** @return false 表示线程被中断，调用方应停止推送 */
  private static boolean sleepFrame() {
    try {
      Thread.sleep(FRAME_INTERVAL_MS);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** 错误帧：前端直接展示 message，所以只把可读原因透出去。 */
  private String errorFrame(String message) {
    try {
      return objectMapper.writeValueAsString(Map.of("type", "error", "message", message));
    } catch (Exception ex) {
      return "{\"type\":\"error\",\"message\":\"知识库调用失败\"}";
    }
  }

  private static String describeFailure(Exception ex) {
    if (ex instanceof IllegalStateException && ex.getMessage() != null) {
      return ex.getMessage();
    }
    return "知识库调用失败：" + ex.getClass().getSimpleName();
  }

  private Map<String, Object> forward(String path, Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (kbServiceToken != null && !kbServiceToken.isBlank()) {
      headers.set("X-Kb-Service-Token", kbServiceToken);
    }
    HttpEntity<Object> entity = new HttpEntity<>(body, headers);
    try {
      Map<String, Object> data = restTemplate.postForObject(kbBaseUrl + path, entity, Map.class);
      if (data == null) {
        throw new IllegalStateException("企业知识库返回为空，请确认知识库服务正常");
      }
      return data;
    } catch (ResourceAccessException ex) {
      throw new IllegalStateException("企业知识库服务暂不可用，请确认知识库已启动（默认 :8765）", ex);
    } catch (HttpClientErrorException | HttpServerErrorException ex) {
      throw new IllegalStateException("企业知识库调用失败（HTTP " + ex.getStatusCode().value() + "）", ex);
    }
  }
}
