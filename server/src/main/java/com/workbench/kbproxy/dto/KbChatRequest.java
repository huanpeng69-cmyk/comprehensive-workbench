package com.workbench.kbproxy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * 知识库 /api/chat 请求体。字段名用 @JsonProperty 精确对齐知识库 Pydantic 的 snake_case。
 *
 * @param topK 缺省 5，未传或传 null 时回落
 * @param minRelevance 缺省 0.3，未传或传 null 时回落
 */
public record KbChatRequest(
    @JsonProperty("question") String question,
    @JsonProperty("history") List<Map<String, String>> history,
    @JsonProperty("top_k") Integer topK,
    @JsonProperty("min_relevance") Double minRelevance
) {

  public KbChatRequest {
    if (history == null) {
      history = List.of();
    }
    if (topK == null) {
      topK = 5;
    }
    if (minRelevance == null) {
      minRelevance = 0.3;
    }
  }
}
