package com.workbench.kbproxy.api;

import com.workbench.audit.application.AuditService;
import com.workbench.common.Result;
import com.workbench.kbproxy.dto.KbChatRequest;
import com.workbench.kbproxy.dto.KbQueryRequest;
import java.util.Map;
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

/**
 * 企业知识库（.pi 项目）桥接：工作台内「智能问答」后端转发。
 * 接口要求工作台 JWT（SecurityConfig 自动拦截）；服务端以只读服务令牌调用知识库
 * /api/chat、/api/query，令牌不进浏览器。知识库侧令牌为空时返回 401/403。
 */
@RestController
@RequestMapping("/api/kb")
public class KbProxyController {

  private final RestTemplate restTemplate;
  private final AuditService auditService;
  private final String kbBaseUrl;
  private final String kbServiceToken;

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
