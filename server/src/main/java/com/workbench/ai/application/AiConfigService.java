package com.workbench.ai.application;

import com.workbench.ai.domain.AiConfig;
import com.workbench.ai.dto.AiConfigTestRequest;
import com.workbench.ai.dto.AiConfigView;
import com.workbench.ai.repository.AiConfigRepository;
import com.workbench.security.CurrentUserHolder;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import com.workbench.ai.RestTemplateProxySupport;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * AI 写作台模型配置。
 *
 * <p>配置有两个来源，优先级为「数据库行 &gt; 环境变量」：
 * <ul>
 *   <li>数据库行：管理员在写作台「模型设置」弹窗里保存，全公司共用，重启后端也不丢；</li>
 *   <li>环境变量：由 {@code start-workbench.bat} 从同目录 {@code .env} 注入
 *       （{@code WORKBENCH_AI_BASE_URL / WORKBENCH_AI_MODEL / WORKBENCH_AI_API_KEY}），
 *       用于不给数据库写权限时的兜底。</li>
 * </ul>
 * 密钥只在服务端流转，{@link #view()} 对外一律脱敏。
 */
@Service
public class AiConfigService {

  /** 生效来源标记：db = 页面保存的配置；env = 环境变量兜底。 */
  public static final String SOURCE_DB = "db";
  public static final String SOURCE_ENV = "env";

  /** 允许修改模型配置的角色，与 AiConfigController 写接口的 @PreAuthorize 保持一致。 */
  private static final List<String> CONFIG_EDITABLE_ROLES = List.of("SYSTEM_ADMIN", "HR_ADMIN");

  private static final int MAX_KEY_LENGTH = 512;

  /** 探测超时：连 5s / 读 20s。刻意比流式生成（read 120s）短——探测不该让页面干等。 */
  private static final int PROBE_CONNECT_TIMEOUT_MS = 5_000;
  private static final int PROBE_READ_TIMEOUT_MS = 20_000;

  /** 单次人话原因的最大长度，避免把上游一整段 HTML 错误页塞进提示。 */
  private static final int MAX_REASON_LENGTH = 200;

  private final AiConfigRepository repository;
  private final Environment environment;
  private final RestTemplate probeRestTemplate;

  // 必须显式标 @Autowired：本类有「2 参生产构造器」和「3 参测试构造器」两个构造器，
  // 谁都不带注解时 Spring 的构造器推断会返回空、退回无参构造并抛
  // No default constructor found（单测能过是因为它不装配容器，只有真起 jar 才暴露）。
  @Autowired
  public AiConfigService(AiConfigRepository repository, Environment environment) {
    this(repository, environment, createProbeRestTemplate());
  }

  /** 包级构造器供测试注入打桩过的 RestTemplate。 */
  AiConfigService(AiConfigRepository repository, Environment environment, RestTemplate probeRestTemplate) {
    this.repository = repository;
    this.environment = environment;
    this.probeRestTemplate = probeRestTemplate;
  }

  private static RestTemplate createProbeRestTemplate() {
    return RestTemplateProxySupport.create(PROBE_CONNECT_TIMEOUT_MS, PROBE_READ_TIMEOUT_MS);
  }

  /** 探测结果。ok=false 时 message 是给人看的原因（HTTP 码 / 超时 / 地址不通）。 */
  public record AiConnectionCheck(boolean ok, String message, long latencyMs) {}

  /** 生成前用的完整配置（含明文密钥，仅限服务端内部调用）。 */
  public record AiSettings(String baseUrl, String model, String apiKey, String source) {}

  /**
   * 给浏览器看的脱敏配置。
   *
   * <p>{@code editable} 由后端按当前登录用户的角色判定，是唯一权威来源——前端不再维护
   * 一份会漂移的角色副本（原先前端硬编码 SYSTEM_ADMIN/HR_ADMIN，后端一放开新角色两边就不同步）。
   */
  public AiConfigView view() {
    AiSettings settings = resolve();
    boolean editable = CurrentUserHolder.currentRoleCodes().stream()
        .anyMatch(CONFIG_EDITABLE_ROLES::contains);
    return AiConfigView.of(
        settings.baseUrl(), settings.model(), settings.apiKey(), settings.source(), editable);
  }

  /** 生成前解析最终配置：页面保存过就优先用库里的，否则回退环境变量。 */
  public AiSettings resolve() {
    AiConfig row = repository.findFirstByOrderByIdAsc().orElse(null);

    String baseUrl = orNull(row == null ? null : row.getBaseUrl());
    String model = orNull(row == null ? null : row.getModel());
    String apiKey = orNull(row == null ? null : row.getApiKey());

    String source = (baseUrl != null || model != null) ? SOURCE_DB : SOURCE_ENV;
    if (baseUrl == null) {
      baseUrl = orNull(environment.getProperty("workbench.ai.base-url", ""));
    }
    if (model == null) {
      model = orNull(environment.getProperty("workbench.ai.model", ""));
    }
    if (apiKey == null) {
      apiKey = orNull(environment.getProperty("workbench.ai.api-key", ""));
    }
    return new AiSettings(baseUrl, model, apiKey, source);
  }

  /** 保存配置（单行 upsert）。apiKey 传空表示不修改，保留库里已有的密钥。 */
  public AiConfig save(String baseUrl, String model, String apiKey, String operator) {
    if (baseUrl == null || !baseUrl.trim().matches("https?://.*")) {
      throw new IllegalArgumentException("接口地址必须以 http:// 或 https:// 开头");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("模型名不能为空");
    }
    if (apiKey != null && apiKey.length() > MAX_KEY_LENGTH) {
      throw new IllegalArgumentException("密钥长度不能超过 " + MAX_KEY_LENGTH + " 个字符");
    }

    AiConfig row = repository.findFirstByOrderByIdAsc().orElseGet(() -> AiConfig.builder().build());
    row.setBaseUrl(baseUrl.trim());
    row.setModel(model.trim());
    if (apiKey != null && !apiKey.isBlank()) {
      row.setApiKey(apiKey.trim());
    }
    row.setUpdatedBy(operator);
    row.setUpdatedAt(LocalDateTime.now());
    return repository.save(row);
  }

  /**
   * 测试连接：真实发一次极简生成请求（max_tokens=1），一次性验证接口地址 / 模型名 / 密钥。
   *
   * <p>三个入参都是「可选覆盖」，语义与 {@link #save} 对齐，避免「测的时候填了新值、保存后还是老的」：
   * 全空 → 用当前生效配置（库 &gt; 环境变量）；部分填 → 缺的补已保存值；apiKey 留空 → 沿用已有密钥。
   * 纯探测，不落库。
   */
  public AiConnectionCheck testConnection(AiConfigTestRequest request) {
    AiSettings saved = resolve();
    String baseUrl = orNull(request == null ? null : request.baseUrl());
    String model = orNull(request == null ? null : request.model());
    String apiKey = orNull(request == null ? null : request.apiKey());
    if (baseUrl == null && model == null && apiKey == null) {
      return probe(saved.baseUrl(), saved.model(), saved.apiKey());
    }
    return probe(
        firstNonNull(baseUrl, saved.baseUrl()),
        firstNonNull(model, saved.model()),
        firstNonNull(apiKey, saved.apiKey()));
  }

  private AiConnectionCheck probe(String baseUrl, String model, String apiKey) {
    // 校验格式而不是直接发请求：否则填成 ftp:// 之类只会落到「连不上这个地址」，把人往错的方向带
    if (baseUrl == null) {
      return new AiConnectionCheck(false, "接口地址还没配，先填一下再测试", 0);
    }
    if (!baseUrl.matches("https?://.*")) {
      return new AiConnectionCheck(false, "接口地址必须以 http:// 或 https:// 开头", 0);
    }
    if (model == null) {
      return new AiConnectionCheck(false, "模型名还没配，先填一下再测试", 0);
    }
    long start = System.currentTimeMillis();
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      if (apiKey != null && !apiKey.isBlank()) {
        headers.setBearerAuth(apiKey);
      }
      HttpEntity<Map<String, Object>> entity = new HttpEntity<>(pingBody(model), headers);
      ResponseEntity<String> response =
          probeRestTemplate.exchange(completionUrl(baseUrl), HttpMethod.POST, entity, String.class);
      long latency = System.currentTimeMillis() - start;
      String body = response.getBody();
      // 光看 200 不够：有些网关会拿 200 回一个错误 JSON，必须验 choices 字段才算真的通
      if (response.getStatusCode().is2xxSuccessful() && body != null && body.contains("\"choices\"")) {
        return new AiConnectionCheck(true, "接口可用：" + model, latency);
      }
      return new AiConnectionCheck(false, "接口有响应，但不是 OpenAI 兼容格式：" + summarize(body), latency);
    } catch (HttpStatusCodeException e) {
      return new AiConnectionCheck(
          false,
          "HTTP " + e.getStatusCode().value() + "：" + summarize(e.getResponseBodyAsString()),
          System.currentTimeMillis() - start);
    } catch (ResourceAccessException e) {
      long latency = System.currentTimeMillis() - start;
      Throwable cause = e.getCause();
      if (cause instanceof SocketTimeoutException) {
        return new AiConnectionCheck(
            false,
            "接口在 " + PROBE_READ_TIMEOUT_MS / 1000 + " 秒内没返回，检查地址或模型是否正在冷启动",
            latency);
      }
      if (cause instanceof UnknownHostException || cause instanceof SocketException) {
        return new AiConnectionCheck(false,
            "连不上这个地址（" + summarize(e.getMessage()) + "）。当前出网：" + RestTemplateProxySupport.describeProxy()
                + "，检查 Base URL 或代理是否开着", latency);
      }
      return new AiConnectionCheck(false,
          "请求没发出去：" + summarize(e.getMessage()) + "。当前出网：" + RestTemplateProxySupport.describeProxy(), latency);
    } catch (Exception e) {
      return new AiConnectionCheck(false, "测试失败：" + summarize(e.getMessage()), 0);
    }
  }

  private static Map<String, Object> pingBody(String model) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "user");
    message.put("content", "ping");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", List.of(message));
    body.put("max_tokens", 1);
    body.put("stream", false);
    return body;
  }

  private static String completionUrl(String baseUrl) {
    String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed + "chat/completions" : trimmed + "/chat/completions";
  }

  /** 压平换行并截断——上游错误页动辄整屏 HTML，不能原样回传到提示里。 */
  private static String summarize(String text) {
    if (text == null || text.isBlank()) {
      return "（上游没给任何说明）";
    }
    String flat = text.replaceAll("\\s+", " ").trim();
    return flat.length() > MAX_REASON_LENGTH ? flat.substring(0, MAX_REASON_LENGTH) + "…" : flat;
  }

  private static String firstNonNull(String value, String fallback) {
    return value != null ? value : fallback;
  }

  private static String orNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
