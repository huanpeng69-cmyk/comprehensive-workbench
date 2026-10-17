package com.workbench.memory.dto;

import java.util.List;

/**
 * 记忆召回结果：既要给 Agent 拼提示词，也要给前端「召回预览」看明细。
 *
 * @param block          可直接拼进 system prompt 的记忆块；没有任何记忆时为<b>空字符串</b>
 *                       （调用方据此跳过拼接，而不是插一段空标题）
 * @param memories       实际进入记忆块的记忆（已按相关度排序、已做预算压缩）
 * @param estimatedTokens 记忆块估算 token 数
 * @param omittedCount   因超出 token 预算而未逐条展开的条数（前端用于提示「还有 N 条较早记忆」）
 */
public record MemoryRecallResult(
    String block,
    List<MemoryView> memories,
    int estimatedTokens,
    int omittedCount) {

  /** 没有可用记忆时的空结果：投影成空字符串块，注入侧零特判成本。 */
  public static MemoryRecallResult empty() {
    return new MemoryRecallResult("", List.of(), 0, 0);
  }
}
