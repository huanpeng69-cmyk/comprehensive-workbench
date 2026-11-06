package com.workbench.ai.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 流式决策里 tool_calls 分片累积的回归：{@link AgentService#accumulateToolCall(Map, Object)}
 * 与 {@link AgentService#buildToolCalls(Map)}。
 *
 * <p>钉住 OpenAI 流式协议的真实形状：第一帧带 {@code id} 与 {@code function.name}，后续帧只带
 * {@code function.arguments} 的<b>片段</b>，同一响应里多个工具用 {@code index} 区分。若把每帧
 * 当成一次独立调用，就会得到只含半截 JSON 的「工具调用」，参数解析必然失败（或更糟：静默丢参数）。
 */
class AgentServiceToolCallAccumulationTest {

  private static Map<Integer, Map<String, Object>> newAcc() {
    // 与 AgentService#streamDecision 里同一实现：按 index 有序，保证多个工具的还原顺序稳定。
    return new TreeMap<>();
  }

  /** 造一帧 delta 里的单个 tool_call 分片（可选字段为 null 时不放入 map，模拟 JSON 里缺该键）。 */
  private static Map<String, Object> piece(
      Integer index, String id, String name, String argsFragment) {
    Map<String, Object> function = new LinkedHashMap<>();
    if (name != null) {
      function.put("name", name);
    }
    if (argsFragment != null) {
      function.put("arguments", argsFragment);
    }
    Map<String, Object> p = new LinkedHashMap<>();
    if (index != null) {
      p.put("index", index);
    }
    if (id != null) {
      p.put("id", id);
    }
    p.put("function", function);
    return p;
  }

  /** 累积一帧（delta.tool_calls 本身是数组）。 */
  private static void frame(Map<Integer, Map<String, Object>> acc, Map<String, Object>... pieces) {
    AgentService.accumulateToolCall(acc, List.of(pieces));
  }

  private static Map<String, Object> functionOf(Map<String, Object> call) {
    @SuppressWarnings("unchecked")
    Map<String, Object> fn = (Map<String, Object>) call.get("function");
    return fn;
  }

  @Test
  void argumentsSplitAcrossFramesAreConcatenated() {
    Map<Integer, Map<String, Object>> acc = newAcc();
    frame(acc, piece(0, "call_1", "get_weather", "{\"ci"));
    frame(acc, piece(0, null, null, "ty\":"));
    frame(acc, piece(0, null, null, "\"北京\"}"));

    List<Map<String, Object>> calls = AgentService.buildToolCalls(acc);

    assertEquals(1, calls.size());
    assertEquals("get_weather", functionOf(calls.get(0)).get("name"));
    assertEquals("{\"city\":\"北京\"}", functionOf(calls.get(0)).get("arguments"));
  }

  @Test
  void twoToolCallsInterleavedByIndexStaySeparate() {
    Map<Integer, Map<String, Object>> acc = newAcc();
    // 真实模型会交错下发多个工具的分片，不能按到达顺序「拼错槽」。
    frame(acc, piece(1, "call_b", "get_weather", "{\"city\":"));
    frame(acc, piece(0, "call_a", "read_kb", "{\"q\":"));
    frame(acc, piece(1, null, null, "\"上海\"}"));
    frame(acc, piece(0, null, null, "\"政策\"}"));

    List<Map<String, Object>> calls = AgentService.buildToolCalls(acc);

    assertEquals(2, calls.size());
    // index 有序：0 在前，与模型给出的下标一致（与到达顺序无关）。
    assertEquals("call_a", calls.get(0).get("id"));
    assertEquals("read_kb", functionOf(calls.get(0)).get("name"));
    assertEquals("{\"q\":\"政策\"}", functionOf(calls.get(0)).get("arguments"));
    assertEquals("call_b", calls.get(1).get("id"));
    assertEquals("get_weather", functionOf(calls.get(1)).get("name"));
    assertEquals("{\"city\":\"上海\"}", functionOf(calls.get(1)).get("arguments"));
  }

  @Test
  void missingIdAndNameAfterFirstFrameAreNotCleared() {
    Map<Integer, Map<String, Object>> acc = newAcc();
    frame(acc, piece(0, "call_1", "read_kb", "{\"q\":\"a\"}"));
    // 后续帧常见两种情况：字段完全缺省，或给了空串（部分兼容实现这么发）——都不能覆盖首帧的值。
    frame(acc, piece(0, null, "", ""));

    List<Map<String, Object>> calls = AgentService.buildToolCalls(acc);

    assertEquals(1, calls.size());
    assertEquals("call_1", calls.get(0).get("id"));
    assertEquals("read_kb", functionOf(calls.get(0)).get("name"));
    assertEquals("{\"q\":\"a\"}", functionOf(calls.get(0)).get("arguments"));
  }

  @Test
  void noArgumentsAtAllYieldsEmptyStringNotNull() {
    Map<Integer, Map<String, Object>> acc = newAcc();
    // 无参工具：模型只会给 id 与 name，arguments 可能整帧缺失。
    frame(acc, piece(0, "call_1", "list_projects", null));

    List<Map<String, Object>> calls = AgentService.buildToolCalls(acc);

    assertEquals(1, calls.size());
    assertEquals("", functionOf(calls.get(0)).get("arguments"));
    assertEquals("list_projects", functionOf(calls.get(0)).get("name"));
  }

  @Test
  void builtCallHasOpenAiShape() {
    Map<Integer, Map<String, Object>> acc = newAcc();
    frame(acc, piece(0, "call_1", "read_kb", "{}"));

    Map<String, Object> call = AgentService.buildToolCalls(acc).get(0);

    assertEquals("call_1", call.get("id"));
    assertEquals("function", call.get("type"));
    Map<String, Object> fn = functionOf(call);
    assertNotNull(fn);
    assertEquals(2, fn.size());
    assertTrue(fn.containsKey("name") && fn.containsKey("arguments"));
  }

  @Test
  void emptyAccumulatorYieldsEmptyList() {
    assertTrue(AgentService.buildToolCalls(newAcc()).isEmpty());
  }

  @Test
  void nonListDeltaCreatesNoSlot() {
    Map<Integer, Map<String, Object>> acc = newAcc();
    AgentService.accumulateToolCall(acc, null);
    AgentService.accumulateToolCall(acc, "not-a-list");
    AgentService.accumulateToolCall(acc, new LinkedHashMap<String, Object>());

    assertTrue(acc.isEmpty());
  }

  @Test
  void emptyToolCallsArrayCreatesNoSlot() {
    Map<Integer, Map<String, Object>> acc = newAcc();
    AgentService.accumulateToolCall(acc, List.of());

    assertTrue(acc.isEmpty());
  }

  @Test
  void missingIndexFallsBackToZero() {
    Map<Integer, Map<String, Object>> acc = newAcc();
    // 极少数兼容实现单工具时不发 index，退化为 0，保证参数不丢。
    frame(acc, piece(null, "call_1", "read_kb", "{\"q\":\"a\"}"));

    List<Map<String, Object>> calls = AgentService.buildToolCalls(acc);

    assertEquals(1, calls.size());
    assertEquals("{\"q\":\"a\"}", functionOf(calls.get(0)).get("arguments"));
  }

  @Test
  void slotWithoutNameKeepsNullRatherThanLiteralNull() {
    Map<Integer, Map<String, Object>> acc = newAcc();
    // 只到 arguments 帧就到流末尾（连接中断）：name 仍为 null，但不能变成字符串 "null"。
    frame(acc, piece(0, "call_1", null, "{\"q\":\"a\"}"));

    Map<String, Object> call = AgentService.buildToolCalls(acc).get(0);

    assertNull(functionOf(call).get("name"));
    assertEquals("call_1", call.get("id"));
    assertEquals("{\"q\":\"a\"}", functionOf(call).get("arguments"));
  }
}
