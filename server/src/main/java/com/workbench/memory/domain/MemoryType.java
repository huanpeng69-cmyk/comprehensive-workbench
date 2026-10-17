package com.workbench.memory.domain;

/**
 * 记忆类型：决定记忆在注入提示词时落到哪一组，也决定检索时的呈现顺序。
 *
 * <p>分三档是认知心理学的常用切分，落到工程上各自的用途不同：</p>
 * <ul>
 *   <li>{@link #SEMANTIC} 语义记忆——关于「用户是谁」的事实与长期偏好（岗位、口径、常驻单位）。
 *       这类内容最稳定，注入时优先级最高。</li>
 *   <li>{@link #PROCEDURAL} 程序记忆——关于「活该怎么干」的做法与格式要求（先出提纲、要表格、不写套话）。
 *       直接约束模型的行为，紧跟语义记忆之后。</li>
 *   <li>{@link #EPISODIC} 情景记忆——关于「做过什么」的具体经历（上周处理过哪起工伤、某次审批的结论）。
 *       时效性最强、也最容易过期，排在最后。</li>
 * </ul>
 */
public enum MemoryType {

  /** 语义记忆：事实与偏好。 */
  SEMANTIC("语义记忆", "关于用户是谁的事实与长期偏好"),

  /** 程序记忆：做法与格式要求。 */
  PROCEDURAL("程序记忆", "关于用户希望怎么做事、以什么形式输出"),

  /** 情景记忆：具体经历。 */
  EPISODIC("情景记忆", "用户过去的具体经历与结论");

  private final String label;
  private final String description;

  MemoryType(String label, String description) {
    this.label = label;
    this.description = description;
  }

  /** 中文名，用于注入提示词的小标题与前端展示。 */
  public String label() {
    return label;
  }

  /** 一句话说明，供前端展示与提示词里的分组解释。 */
  public String description() {
    return description;
  }

  /**
   * 解析类型串（大小写不敏感，忽略首尾空白）。
   *
   * <p>空值回落到 {@link #SEMANTIC}——调用方（前端/Agent）不写类型时，最安全的默认是「事实与偏好」；
   * 而写错了的非法值必须报错，绝不静默兜底，否则用户以为存成了情景记忆、实际却进了别的档。</p>
   */
  public static MemoryType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return SEMANTIC;
    }
    String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
    for (MemoryType type : values()) {
      if (type.name().equals(normalized)) {
        return type;
      }
    }
    throw new IllegalArgumentException("未知的记忆类型：" + raw + "（可选 SEMANTIC / PROCEDURAL / EPISODIC）");
  }
}
