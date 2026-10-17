package com.workbench.memory.domain;

/**
 * 记忆来源：区分「用户自己写的」与「AI 会话里沉淀的」，用于管理列表展示与审计追溯。
 *
 * <p>取值与 {@code user_memory.source} 的 CHECK 约束一一对应；实体用
 * {@code @Enumerated(STRING)} 落库，因此枚举名即库中字符串。</p>
 */
public enum MemorySource {

  /** 用户通过记忆管理接口手写。 */
  MANUAL("用户手写"),

  /** 由 AI 会话（Agent）调用记忆接口沉淀。 */
  AGENT("AI 会话沉淀");

  private final String label;

  MemorySource(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
