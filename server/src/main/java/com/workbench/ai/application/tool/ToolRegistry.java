package com.workbench.ai.application.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 已注册工具的容器。Spring 会把所有 {@link AgentTool} 实现收集进来；后续加工具（如
 * search_regulations）只要再写一个 @Component 即可，无需改编排逻辑。
 */
@Component
public class ToolRegistry {

  private final Map<String, AgentTool> tools = new LinkedHashMap<>();

  public ToolRegistry(List<AgentTool> agentTools) {
    for (AgentTool t : agentTools) {
      tools.put(t.name(), t);
    }
  }

  public AgentTool get(String name) {
    return tools.get(name);
  }

  public boolean isEmpty() {
    return tools.isEmpty();
  }

  /** 生成 OpenAI function calling 的 tools 数组（外层 type=function / function 包裹）。 */
  public List<Map<String, Object>> openAiToolSpecs() {
    List<Map<String, Object>> specs = new ArrayList<>();
    for (AgentTool t : tools.values()) {
      Map<String, Object> fn = new LinkedHashMap<>();
      fn.put("name", t.name());
      fn.put("description", t.description());
      fn.put("parameters", t.parametersSchema());
      Map<String, Object> spec = new LinkedHashMap<>();
      spec.put("type", "function");
      spec.put("function", fn);
      specs.add(spec);
    }
    return specs;
  }

  /** 按名称筛选：null 表示全部（兼容不传 tools 字段的旧客户端）；空列表表示不启用任何工具。 */
  public List<Map<String, Object>> openAiToolSpecs(List<String> enabled) {
    if (enabled == null) {
      return openAiToolSpecs();
    }
    List<Map<String, Object>> specs = new ArrayList<>();
    for (Map<String, Object> spec : openAiToolSpecs()) {
      Map<?, ?> fn = (Map<?, ?>) spec.get("function");
      if (enabled.contains(fn.get("name"))) {
        specs.add(spec);
      }
    }
    return specs;
  }
}
