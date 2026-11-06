package com.workbench.ai.application;

import com.workbench.ai.application.tool.AgentTool;
import com.workbench.ai.application.tool.ToolRegistry;
import com.workbench.ai.application.tool.ToolInvoker;
import com.workbench.ai.application.tool.ToolCaller;
import com.workbench.ai.dto.AgentMessage;
import com.workbench.ai.dto.AgentRequest;
import com.workbench.audit.application.AuditService;
import com.workbench.memory.application.MemoryService;
import com.workbench.memory.dto.MemoryRecallResult;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.security.CurrentUserHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import com.workbench.ai.RestTemplateProxySupport;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  /** 整段对话历史的字符预算，超出则从最旧的消息开始省略。60_000 字符给模型窗口留足余量。 */
  private static final int MAX_HISTORY_CHARS = 60_000;
  private static final long STREAM_TIMEOUT_MS = 180_000;
  /** Agent 最多循环轮次：每轮最多可能触发一次工具调用。 */
  private static final int MAX_AGENT_ITERATIONS = 6;

  private static final String DEFAULT_SYSTEM_PROMPT =
      "你是一个通用的智能助手，善于理解用户意图，并在需要时调用可用的工具获取信息，"
          + "再给出准确、条理清晰、用中文表达的回答。不要编造不确定的事实；信息不足时坦诚说明。";
  private static final Logger log = LoggerFactory.getLogger(AgentService.class);

  private final AiConfigService configService;
  private final MemoryService memoryService;
  private final AuditService auditService;
  private final ToolRegistry toolRegistry;
  private final RestTemplate restTemplate;
  /** 工具执行器：调用前授权、超时隔离、逐次审计（见 {@link ToolInvoker}）。 */
  private final ToolInvoker toolInvoker;
  /**
   * 数据范围判定：只在主线程用一次（{@code hasCompanyScope} 要查角色表），
   * 结果随 {@link ToolCaller} 传给工具线程。未注入时视为「无全公司范围」。
   */
  private final DataScopeResolver dataScopeResolver;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * 流式生成线程池：每个 Agent 请求独占一个线程直到流结束，必须有界（见 AiWritingService 注释）。
   */
  private final ExecutorService executor = new ThreadPoolExecutor(
      8, 8, 0L, TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());

  @Autowired
  public AgentService(
      AiConfigService configService, AuditService auditService, ToolRegistry toolRegistry,
      MemoryService memoryService, DataScopeResolver dataScopeResolver) {
    this(configService, auditService, toolRegistry, memoryService, createRestTemplate(),
        dataScopeResolver);
  }

  /** 包级构造器供测试注入打桩过的 RestTemplate；不注入数据范围判定（视为无全公司范围）。 */
  AgentService(
      AiConfigService configService, AuditService auditService,
      ToolRegistry toolRegistry, MemoryService memoryService, RestTemplate restTemplate) {
    this(configService, auditService, toolRegistry, memoryService, restTemplate, null);
  }

  /** 包级构造器：额外注入数据范围判定，供同包测试断言项目范围判定的取值。 */
  AgentService(
      AiConfigService configService, AuditService auditService, ToolRegistry toolRegistry,
      MemoryService memoryService, RestTemplate restTemplate, DataScopeResolver dataScopeResolver) {
    this.configService = configService;
    this.memoryService = memoryService;
    this.auditService = auditService;
    this.toolRegistry = toolRegistry;
    this.restTemplate = restTemplate;
    this.dataScopeResolver = dataScopeResolver;
    // 工具执行器：授权判定、超时隔离、逐次审计都在其中，编排层只负责拿回文本结果。
    this.toolInvoker = new ToolInvoker(auditService);
  }

  @PreDestroy
  public void destroy() {
    executor.shutdownNow();
    toolInvoker.shutdown();
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

    // 记忆注入必须在主线程完成（与 username 同理）：SecurityContext 不随线程池传递。
    // 检索失败只降级为「本轮不注入」，绝不打断对话。
    String effectiveSystemPrompt = withMemoryBlock(systemPrompt, request);

    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
    // 安全上下文不会自动传到线程池：调用者身份必须在主线程取出，打包成 ToolCaller 显式传给 Agent 线程。
    // 在 Agent 线程里读 CurrentUserHolder 只会得到空值——角色判定会恒判「无权限」，
    // 项目范围也会恒判「无上下文」，两者都是把授权判定变成静默失效。
    ToolCaller caller = resolveCaller();
    // 客户端断开 / 超时后置位：让 Agent 循环不再继续消耗 LLM 调用，也不再执行后续工具。
    // 注意只影响「后续」步骤——正在跑的模型请求与工具调用靠各自的超时兜底。
    AtomicBoolean cancelled = new AtomicBoolean(false);
    emitter.onCompletion(() -> cancelled.set(true));
    emitter.onError(ex -> cancelled.set(true));
    emitter.onTimeout(() -> {
      cancelled.set(true);
      log.warn("Agent 流式响应超时（{}ms），已标记取消", STREAM_TIMEOUT_MS);
    });
    try {
      executor.execute(
          () -> runAgent(request, effectiveSystemPrompt, baseUrl, model, apiKey,
              caller, emitter, cancelled));
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

  /**
   * 主线程取调用者身份快照（包级可见，供同包测试断言取值逻辑）。
   *
   * <p>{@code companyScope} 必须在这里算完：{@link DataScopeResolver#hasCompanyScope()} 要查角色表，
   * 放到工具线程既拿不到安全上下文，又多一次线程内的库访问。未注入 resolver 时保守取 false——
   * 宁可让声明了 {@link AgentTool#needsProjectScope()} 的工具被拒绝，也不放行越权查询。
   */
  ToolCaller resolveCaller() {
    Set<String> roles = new LinkedHashSet<>(CurrentUserHolder.currentRoleCodes());
    boolean companyScope = dataScopeResolver != null && dataScopeResolver.hasCompanyScope();
    return new ToolCaller(CurrentUserHolder.currentUsername(), roles,
        CurrentUserHolder.currentProjectId(), CurrentUserHolder.currentCompanyId(), companyScope);
  }

  /**
   * 把当前用户的长期记忆块拼到系统提示词之后；无记忆时原样返回（包级可见，供同包测试直接断言）。
   *
   * <p>拼在 system 而不是追加一条 user 消息：记忆是「背景约束」而不是「用户这轮说的话」，
   * 混进对话历史会让模型误以为用户刚提了这些内容，也会污染后续轮次的历史。</p>
   */
  String withMemoryBlock(String systemPrompt, AgentRequest request) {
    String block = resolveMemoryBlock(request);
    return block.isEmpty() ? systemPrompt : systemPrompt + "\n\n" + block;
  }

  /**
   * 检索当前用户的记忆块。
   *
   * <p><b>失败只降级，绝不抛出</b>：记忆是增强项，查库/打分异常不能让整轮对话挂掉，
   * 所以这里兜住所有异常并只留一条 warn 日志。</p>
   */
  private String resolveMemoryBlock(AgentRequest request) {
    try {
      MemoryRecallResult recalled = memoryService.recallForPrompt(lastUserMessage(request));
      if (recalled == null || recalled.block() == null) {
        return "";
      }
      return recalled.block().trim();
    } catch (Exception ex) {
      log.warn("用户记忆检索失败，本轮对话不做记忆注入：{}", ex.getMessage());
      return "";
    }
  }

  /**
   * 最后一条 user 消息的正文，作为记忆检索的话题（包级可见，供同包测试直接断言）。
   *
   * <p>空串表示「没有明确话题」，检索侧会退化成按重要度 + 新鲜度排序，而不是报错。</p>
   */
  static String lastUserMessage(AgentRequest request) {
    if (request == null || request.messages() == null) {
      return "";
    }
    String last = "";
    for (AgentMessage message : request.messages()) {
      if (message != null && "user".equals(message.role())
          && message.content() != null && !message.content().isBlank()) {
        last = message.content();
      }
    }
    return last;
  }

  /** 历史压缩结果：保留的消息与被省略的条数（供提示语与排查用）。 */
  record TrimmedHistory(List<AgentMessage> kept, int dropped) {

    TrimmedHistory {
      kept = kept == null ? List.of() : kept;
    }
  }

  /**
   * 按总字符预算压缩对话历史：从最旧的消息开始省略，最新一条（本轮提问）无条件保留。
   *
   * <p>为什么必须有这一层：客户端每次都把<b>完整</b>历史发上来，服务端若原样透传，
   * 请求体会随轮次线性增长，长对话最终撞上模型上下文窗口，用户只会看到一条看不懂的 400。
   * 单条截断（{@link #MAX_CONTENT_LENGTH}）只防单条超长，防不住总量。</p>
   *
   * <p>为什么按字符而不是 token：tokenizer 需要新增依赖，而字符数是同倍率的保守上界，
   * 用来防「无限增长」已经够用。</p>
   */
  static TrimmedHistory trimHistory(List<AgentMessage> history) {
    if (history == null || history.isEmpty()) {
      return new TrimmedHistory(List.of(), 0);
    }
    int remaining = MAX_HISTORY_CHARS;
    int keepFrom = history.size();
    for (int i = history.size() - 1; i >= 0; i--) {
      AgentMessage m = history.get(i);
      int len = m == null || m.content() == null ? 0 : m.content().length();
      // 最新一条无论多长都保留：宁可超预算，也不能把用户这轮的提问丢掉。
      if (i != history.size() - 1 && len > remaining) {
        break;
      }
      remaining -= len;
      keepFrom = i;
    }
    return new TrimmedHistory(new ArrayList<>(history.subList(keepFrom, history.size())), keepFrom);
  }

  /** 历史被省略时的提示语：明确告诉模型「更早的对话你看不到」，避免它假装记得。 */
  static String omittedNote(int dropped) {
    return "\n\n（说明：更早的 " + dropped + " 条对话因长度限制已省略；如需其中信息，请直接询问用户。）";
  }

  private void runAgent(
      AgentRequest request, String systemPrompt, String baseUrl, String model,
      String apiKey, ToolCaller caller, SseEmitter emitter, AtomicBoolean cancelled) {
    // 用户名只用于审计与总账：从身份快照取，不再单独占一个参数。
    String username = caller == null ? null : caller.username();
    try {
      // 基础消息：系统提示词 + 客户端发来的历史（文本角色）。
      // 历史压缩：先按总字符预算淘汰最旧的消息，再逐条截断（理由见 trimHistory）。
      TrimmedHistory trimmed = trimHistory(request.messages());
      String systemContent = trimmed.dropped() > 0
          ? systemPrompt + omittedNote(trimmed.dropped())
          : systemPrompt;
      List<Map<String, Object>> messages = new ArrayList<>();
      messages.add(Map.of("role", "system", "content", systemContent));
      for (AgentMessage m : trimmed.kept()) {
        String role = m.role() == null ? "user" : m.role();
        String content = m.content() == null ? "" : m.content();
        if (content.length() > MAX_CONTENT_LENGTH) {
          content = content.substring(0, MAX_CONTENT_LENGTH) + "…";
        }
        messages.add(Map.of("role", role, "content", content));
      }

      List<Map<String, Object>> toolSpecs = toolRegistry.openAiToolSpecs(request.tools());

      boolean answered = false;
      // 直连模型模式：未启用任何工具时跳过决策往返，直接流式作答——
      // 否则决策调用期间 UI 只能干等「思考中」，且多花一整轮 LLM 往返。
      if (!toolSpecs.isEmpty()) {
        for (int iter = 0; iter < MAX_AGENT_ITERATIONS; iter++) {
          // 客户端已断开：不再发起新一轮决策调用（每轮都是一次计费请求），但补一条 AI_AGENT 总账，
          // 保证「有过这次对话且提前结束」在审计上不失踪。
          if (cancelled.get()) {
            logAgentQuietly(username, systemPrompt,
                "客户端已断开，Agent 在第 " + (iter + 1) + " 轮决策前结束（未记录对话正文）。");
            return;
          }
          // 1) 决策调用（流式，带工具）：文本 delta 直接当答案推给前端，tool_calls 分片累积后执行。
          DecisionStream decision =
              streamDecision(baseUrl, model, apiKey, messages, toolSpecs, emitter);
          if (decision.clientGone()) {
            logAgentQuietly(username, systemPrompt,
                "客户端连接已断开，Agent 循环提前结束（未记录对话正文）。");
            return;
          }
          List<Map<String, Object>> toolCalls = decision.toolCalls();

          if (toolCalls.isEmpty()) {
            // 2a) 纯文本：上面已经边收边发，不再二次调用模型（旧实现会在这里重发一次流式请求）。
            // 一个字都没吐时（罕见）留 answered=false，交给循环外的兜底 streamAnswer 再要一次。
            answered = decision.assistantMessage().containsKey("content");
            break;
          }

          // 2b) 有工具调用：把 assistant(tool_calls) 留在上下文，逐个执行并回灌。
          messages.add(decision.assistantMessage());
          for (Map<String, Object> tc : toolCalls) {
            Map<String, Object> fn = asMap(tc.get("function"));
            String id = asString(tc.get("id"));
            String name = asString(fn.get("name"));
            String rawArgs = asString(fn.get("arguments"));
            Map<String, Object> args = parseArgs(rawArgs);

            if (!safeSend(emitter, event("tool_call", Map.of("id", id, "name", name, "args", rawArgs)))) {
              logAgentQuietly(username, systemPrompt, "客户端连接已断开，Agent 循环提前结束（未记录对话正文）。");
              return;
            }
            AgentTool tool = toolRegistry.get(name);
            // 未知工具保持原降级文案；已知工具统一经 ToolInvoker：角色判定 → 超时隔离执行 → 逐次审计。
            String result = (tool == null)
                ? "【未知工具】" + name
                : toolInvoker.invoke(caller, tool, rawArgs, args, cancelled);
            if (!safeSend(emitter, event("tool_result", Map.of("id", id, "content", truncate(result, 4000))))) {
              logAgentQuietly(username, systemPrompt, "客户端连接已断开，Agent 循环提前结束（未记录对话正文）。");
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
      // 客户端已断开时不补：这次调用只会立刻失败，白花一次模型请求。
      if (!answered && !cancelled.get()) {
        streamAnswer(baseUrl, model, apiKey, messages, emitter);
      }

      // 取消也算「有过这次对话」，但结果口径要如实：不能把提前收尾记成正常完成。
      logAgentQuietly(username, systemPrompt, cancelled.get()
          ? "客户端已断开，Agent 本轮提前收尾（未记录对话正文）。"
          : "使用通用 Agent 对话（可能含工具调用），未记录对话正文。");
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
      // 失败同样要留总账：模型 500、断流是本功能最常见的故障，审计里什么都不记等于排查无门。
      // 放在发帧之后，先保证前端能收到错误信息，再补审计。
      try {
        auditService.failed(username, "AI_AGENT", "AI_AGENT", promptTarget(systemPrompt),
            "会话失败：" + truncate(String.valueOf(ex.getMessage()), 200) + "（未记录对话正文）",
            "500");
      } catch (Exception auditError) {
        log.warn("AI_AGENT 失败审计写入失败：{}", auditError.getMessage());
      }
    }
  }

  /**
   * AI_AGENT 总账的「吞异常」写入出口。
   *
   * <p>审计是副作用（ADR-M012 契约 2）：写失败只能记日志，绝不能让已经跑完的对话变成失败，
   * 也不能在收尾路径上二次抛异常。正常结束、客户端断开、模型失败三条路径都经这里或等价兜底。</p>
   */
  private void logAgentQuietly(String username, String systemPrompt, String detail) {
    try {
      auditService.log(username, "AI_AGENT", "AI_AGENT", promptTarget(systemPrompt), detail);
    } catch (Exception ex) {
      log.warn("AI_AGENT 审计写入失败：{}", ex.getMessage());
    }
  }

  /** AI_AGENT 总账的 targetId：截断后的提示词摘要，不落完整提示词（可能夹带用户隐私）。 */
  private static String promptTarget(String systemPrompt) {
    return systemPrompt.length() > 40 ? systemPrompt.substring(0, 40) + "…" : systemPrompt;
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

  /** 流式决策调用的产物：可直接回灌上下文的 assistant 消息 + 待执行的工具调用 + 客户端是否已断开。 */
  record DecisionStream(Map<String, Object> assistantMessage,
      List<Map<String, Object>> toolCalls, boolean clientGone) {
  }

  /**
   * 流式决策调用：带 tools 发一次 {@code stream:true} 请求，边收边判。
   *
   * <p>为什么决策也要流式：非流式决策拿到纯文本后，旧实现会<b>再发一次</b>流式请求把同一段答案
   * 重新生成一遍——模型被调用两次，第一次的输出被直接丢弃。改成流式后，文本 delta 就是答案，
   * 收到即推给前端，不再需要第二趟。tool_calls 的 delta 是分片到达的（{@code arguments} 会被切成
   * 多段 JSON），必须在 {@link #accumulateToolCall} 里按 {@code index} 拼完才能解析。</p>
   *
   * <p>模型同时吐文本和 tool_calls 时（少见）：已经流出的文本不回收——那本来就是模型的输出，
   * 回收等于骗用户；随后照常执行工具，最终答案在下一轮补上。</p>
   */
  private DecisionStream streamDecision(
      String baseUrl, String model, String apiKey,
      List<Map<String, Object>> messages, List<Map<String, Object>> toolSpecs,
      SseEmitter emitter) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", model);
    payload.put("messages", messages);
    payload.put("tools", toolSpecs);
    payload.put("tool_choice", "auto");
    payload.put("temperature", 0.7);
    payload.put("stream", true);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (apiKey != null && !apiKey.isBlank()) {
      headers.setBearerAuth(apiKey);
    }
    String bodyJson = writeJson(payload);

    StringBuilder text = new StringBuilder();
    Map<Integer, Map<String, Object>> acc = new TreeMap<>();
    // extractor 里只能 return 一个值，客户端断开的原因用这个标记带出来。
    AtomicBoolean clientGone = new AtomicBoolean(false);
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
              throw new IllegalStateException("AI 服务请求失败（HTTP " + status + "）："
                  + truncate(readStream(response.getBody()), 500));
            }
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
              String line;
              while ((line = reader.readLine()) != null) {
                String data = parseSseData(line);
                if (data == null || "[DONE]".equals(data)) {
                  continue;
                }
                Map<String, Object> delta = parseDelta(data);
                if (delta == null) {
                  continue;
                }
                Object content = delta.get("content");
                if (content instanceof String s && !s.isEmpty()) {
                  text.append(s);
                  if (!safeSend(emitter, event("message", Map.of("content", s)))) {
                    clientGone.set(true);
                    return null;
                  }
                }
                accumulateToolCall(acc, delta.get("tool_calls"));
              }
            }
            return null;
          });
    } catch (IllegalStateException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("AI 流式决策失败（当前出网："
          + RestTemplateProxySupport.describeProxy() + "）：" + truncate(ex.getMessage(), 300));
    }

    List<Map<String, Object>> toolCalls = buildToolCalls(acc);
    Map<String, Object> assistant = new LinkedHashMap<>();
    assistant.put("role", "assistant");
    if (text.length() > 0) {
      assistant.put("content", text.toString());
    }
    if (!toolCalls.isEmpty()) {
      assistant.put("tool_calls", toolCalls);
    }
    return new DecisionStream(assistant, toolCalls, clientGone.get());
  }

  /** 解析一个流式帧里的 choices[0].delta；非 JSON 心跳帧或空 delta 返回 null。 */
  private Map<String, Object> parseDelta(String data) {
    try {
      Map<String, Object> node = objectMapper.readValue(data, Map.class);
      Object choices = node.get("choices");
      if (choices instanceof List<?> list && !list.isEmpty()) {
        Map<String, Object> delta = asMap(asMap(list.get(0)).get("delta"));
        return delta.isEmpty() ? null : delta;
      }
    } catch (Exception e) {
      // 非 JSON 心跳帧，忽略
    }
    return null;
  }

  /**
   * 累积一帧里的 {@code delta.tool_calls}。
   *
   * <p>OpenAI 流式的 tool_call 是分片下发的：第一帧带 {@code id} 与 {@code function.name}，
   * 后续帧只带 {@code arguments} 的<b>片段</b>，同一响应里多个工具用 {@code index} 区分。
   * 所以必须按下标归并、把字符串累加，不能逐帧当成独立调用（那会解析出半截 JSON）。</p>
   */
  static void accumulateToolCall(Map<Integer, Map<String, Object>> acc, Object deltaToolCalls) {
    if (!(deltaToolCalls instanceof List<?> list)) {
      return;
    }
    for (Object item : list) {
      Map<String, Object> piece = asMap(item);
      int index = 0;
      Object rawIndex = piece.get("index");
      if (rawIndex instanceof Number n) {
        index = n.intValue();
      }
      Map<String, Object> slot = acc.computeIfAbsent(index, k -> {
        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("id", null);
        fresh.put("name", null);
        fresh.put("arguments", new StringBuilder());
        return fresh;
      });
      Object id = piece.get("id");
      if (id instanceof String s && !s.isEmpty()) {
        slot.put("id", s);
      }
      Map<String, Object> fn = asMap(piece.get("function"));
      Object name = fn.get("name");
      if (name instanceof String s && !s.isEmpty()) {
        slot.put("name", s);
      }
      Object args = fn.get("arguments");
      if (args instanceof String s && !s.isEmpty()) {
        ((StringBuilder) slot.get("arguments")).append(s);
      }
    }
  }

  /** 把累积结果还原成 OpenAI 的 tool_calls 数组，可直接回灌进 assistant 消息。 */
  static List<Map<String, Object>> buildToolCalls(Map<Integer, Map<String, Object>> acc) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> slot : acc.values()) {
      Map<String, Object> fn = new LinkedHashMap<>();
      fn.put("name", slot.get("name"));
      fn.put("arguments", String.valueOf(slot.get("arguments")));
      Map<String, Object> call = new LinkedHashMap<>();
      call.put("id", slot.get("id"));
      call.put("type", "function");
      call.put("function", fn);
      result.add(call);
    }
    return result;
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
    Map<String, Object> delta = parseDelta(data);
    Object content = delta == null ? null : delta.get("content");
    return content instanceof String s ? s : null;
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
