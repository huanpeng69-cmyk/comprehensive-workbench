package com.workbench.kbproxy.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * KbChatRequest 的缺省回落约定。
 *
 * <p>知识库的 Pydantic 模型把 {@code history} 声明为 {@code list[HistoryEntry]}（给的是
 * {@code default_factory=list}，但显式传 {@code null} 仍会 422）。因此本 DTO 必须在缺省时
 * 回落到空列表，不能把 {@code null} 透传给知识库——否则工作台桥接接口会以
 * 「企业知识库调用失败（HTTP 422）」回给前端（2026-10-14）。
 */
class KbChatRequestTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void missingHistoryFallsBackToEmptyListNotNull() throws Exception {
    // 前端只传 question 时，history 不得为 null，否则知识库 Pydantic 报 422
    KbChatRequest request = MAPPER.readValue("{\"question\":\"年休假怎么规定的\"}", KbChatRequest.class);

    assertEquals(List.of(), request.history(), "history 缺省必须回落为空列表");
    assertEquals(5, request.topK(), "topK 缺省回落 5");
    assertEquals(0.3, request.minRelevance(), "minRelevance 缺省回落 0.3");
  }

  @Test
  void serializedBodyKeepsSnakeCaseFieldNames() throws Exception {
    // 字段名必须与知识库 Pydantic 对齐（snake_case），改这里等于改对外契约
    String json = MAPPER.writeValueAsString(new KbChatRequest("q", null, null, null));

    assertEquals(true, json.contains("\"history\":[]"), "缺省 history 必须序列化成空数组，实际：" + json);
    assertEquals(true, json.contains("\"top_k\":5"), "top_k 必须保持 snake_case，实际：" + json);
    assertEquals(true, json.contains("\"min_relevance\":0.3"), "min_relevance 必须保持 snake_case，实际：" + json);
  }
}
