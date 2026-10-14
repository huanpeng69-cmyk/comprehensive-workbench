package com.workbench.ai.application.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 工具筛选语义：null = 全部（兼容旧客户端）；空列表 = 不启用任何工具（直连模型）；
 * 按名称筛选。防止「传 [] 想关工具，结果启用了全部」这类回归。
 */
class ToolRegistryTest {

  private static AgentTool stub(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return name + " stub";
      }

      @Override
      public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of());
      }

      @Override
      public String execute(Map<String, Object> args) {
        return name;
      }
    };
  }

  private static ToolRegistry registry() {
    return new ToolRegistry(List.of(stub("query_knowledge_base"), stub("search_regulations")));
  }

  @Test
  void nullEnablesAllTools() {
    assertEquals(2, registry().openAiToolSpecs(null).size());
  }

  @Test
  void emptyListEnablesNoTools() {
    assertTrue(registry().openAiToolSpecs(List.of()).isEmpty(), "tools: [] 必须表示不启用任何工具");
  }

  @Test
  void filtersByName() {
    List<Map<String, Object>> specs = registry().openAiToolSpecs(List.of("query_knowledge_base"));
    assertEquals(1, specs.size());
    Map<?, ?> fn = (Map<?, ?>) specs.get(0).get("function");
    assertEquals("query_knowledge_base", fn.get("name"));
  }

  @Test
  void unknownNameYieldsEmpty() {
    assertTrue(registry().openAiToolSpecs(List.of("no_such_tool")).isEmpty());
  }
}
