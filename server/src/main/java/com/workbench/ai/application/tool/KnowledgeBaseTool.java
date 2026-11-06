package com.workbench.ai.application.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.workbench.ai.RestTemplateProxySupport;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * 企业知识库检索工具：复用工作台既有的知识库桥接约定（base-url + 服务令牌头
 * X-Kb-Service-Token），调用知识库 /api/query 拿到相关片段。
 *
 * <p>知识库未配置、未启动、鉴权失败等任何情况都降级为说明性文本返回给模型，不抛异常，
 * 这样 Agent 能自己向用户解释「知识库暂不可用」，而不是整轮对话崩掉。
 */
@Component
public class KnowledgeBaseTool implements AgentTool {

  private final String kbBaseUrl;
  private final String kbServiceToken;
  private final RestTemplate restTemplate;

  @Autowired
  public KnowledgeBaseTool(
      @Value("${workbench.kb.base-url:}") String kbBaseUrl,
      @Value("${workbench.kb.service-token:}") String kbServiceToken) {
    this(kbBaseUrl, kbServiceToken, createRestTemplate());
  }

  /** 包级构造器供测试注入打桩过的 RestTemplate。 */
  KnowledgeBaseTool(String kbBaseUrl, String kbServiceToken, RestTemplate restTemplate) {
    this.kbBaseUrl = kbBaseUrl;
    this.kbServiceToken = kbServiceToken == null ? "" : kbServiceToken;
    this.restTemplate = restTemplate;
  }

  private static RestTemplate createRestTemplate() {
    // 读超时 30 秒：必须小于工具的 timeoutMs（35 秒），让 HTTP 层先失败并返回可自解释的降级文案。
    return RestTemplateProxySupport.create(5_000, 30_000);
  }

  @Override
  public String name() {
    return "query_knowledge_base";
  }

  @Override
  public String description() {
    return "在企业知识库中检索与用户问题相关的制度、规范、资料等，返回相关片段与来源。"
        + "当用户询问公司制度、规定、流程、资料，或需要事实依据、引用文件时使用。";
  }

  @Override
  public Map<String, Object> parametersSchema() {
    Map<String, Object> query = new LinkedHashMap<>();
    query.put("type", "string");
    query.put("description", "检索问题或关键词，尽量具体");
    Map<String, Object> topK = new LinkedHashMap<>();
    topK.put("type", "integer");
    topK.put("description", "返回的相关片段数量，默认 5");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("query", query);
    props.put("top_k", topK);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", props);
    schema.put("required", List.of("query"));
    return schema;
  }

  /**
   * 知识库是公司级制度资料（不区分项目部），且内部按用户问题检索，故声明为全局只读工具。
   */
  @Override
  public boolean needsProjectScope() {
    return false;
  }

  /**
   * 比默认的 15 秒放宽：知识库要跑一次向量检索 + 生成，正常耗时明显更长。
   *
   * <p>但必须严格大于下方 RestTemplate 的读超时（30 秒）：让 HTTP 层先超时，
   * 由本工具的 catch 分支返回「【知识库暂不可用】…」这种能自解释的文案；
   * 若把工具超时设得更短，模型只会看到笼统的「工具超时」，也白白多占一个工具线程。</p>
   */
  @Override
  public int timeoutMs() {
    return 35_000;
  }
  @Override
  public String execute(Map<String, Object> args) {
    if (kbBaseUrl == null || kbBaseUrl.isBlank()) {
      return "【知识库未配置】服务端未配置企业知识库地址（workbench.kb.base-url），无法检索。";
    }
    String query = asString(args.get("query"));
    if (query == null) {
      return "【参数缺失】工具调用未提供 query。";
    }
    int topK = asInt(args.get("top_k"), 5);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("question", query);
    body.put("top_k", topK);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (!kbServiceToken.isBlank()) {
      headers.set("X-Kb-Service-Token", kbServiceToken);
    }
    HttpEntity<Object> entity = new HttpEntity<>(body, headers);
    try {
      Map<String, Object> data =
          restTemplate.postForObject(kbBaseUrl + "/api/query", entity, Map.class);
      if (data == null) {
        return "【知识库返回为空】请确认知识库服务正常。";
      }
      return summarize(data);
    } catch (ResourceAccessException ex) {
      return "【知识库暂不可用】连接失败：" + truncate(ex.getMessage(), 120)
          + "。请确认知识库已启动（默认 :8765）。";
    } catch (HttpClientErrorException | HttpServerErrorException ex) {
      return "【知识库调用失败】HTTP " + ex.getStatusCode().value() + "："
          + truncate(ex.getResponseBodyAsString(), 120);
    } catch (Exception ex) {
      return "【知识库调用异常】" + truncate(ex.getMessage(), 120);
    }
  }

  /** 把知识库返回的 answer / results 压成一段文本喂回模型；都拿不到就回落整段 JSON。 */
  private static String summarize(Map<String, Object> data) {
    StringBuilder sb = new StringBuilder();
    Object answer = data.get("answer");
    if (answer != null && !String.valueOf(answer).isBlank()) {
      sb.append(answer).append("\n\n");
    }
    Object results = data.get("results");
    if (results instanceof List<?> list) {
      int i = 1;
      for (Object r : list) {
        if (r instanceof Map<?, ?> m) {
          Object content = m.get("content");
          // 没有正文的片段直接跳过：append(null) 会拼出字面 "null"，模型会把占位当成真实内容引用。
          String body = content == null ? "" : String.valueOf(content).trim();
          if (body.isEmpty()) {
            continue;
          }
          Object source = m.get("source");
          sb.append("【片段 ").append(i++).append("】");
          if (source != null && !String.valueOf(source).isBlank()) {
            sb.append("来源：").append(source).append("\n");
          }
          sb.append(body).append("\n");
        }
      }
    }
    String text = sb.toString().trim();
    if (text.isBlank()) {
      return truncate(data.toString(), 4000);
    }
    return truncate(text, 4000);
  }

  private static String asString(Object o) {
    return o == null ? null : String.valueOf(o).trim();
  }

  private static int asInt(Object o, int def) {
    if (o instanceof Number n) {
      return n.intValue();
    }
    if (o instanceof String s && s.matches("\\d+")) {
      return Integer.parseInt(s);
    }
    return def;
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return "";
    }
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
