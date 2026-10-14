package com.workbench.kbproxy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 知识库 /api/query 请求体。字段名用 @JsonProperty 精确对齐知识库 Pydantic 的 snake_case。
 *
 * @param topK 缺省 5，未传或传 null 时回落
 */
public record KbQueryRequest(
    @JsonProperty("question") String question,
    @JsonProperty("top_k") Integer topK
) {

  public KbQueryRequest {
    if (topK == null) {
      topK = 5;
    }
  }
}
