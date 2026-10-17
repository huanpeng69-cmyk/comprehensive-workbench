package com.workbench.memory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 记忆召回请求：给定当前话题，取回要注入提示词的那一小块记忆。
 *
 * <p>三个字段全可空：前端「召回预览」面板可以什么都不传，服务端按
 * {@code MemoryContextBuilder} 的默认 topK 与 token 预算执行。</p>
 *
 * @param query       当前话题/问题；为空时退化为「按重要度 + 新鲜度」排序
 * @param topK        最多取几条，1..30，可空
 * @param tokenBudget 记忆块 token 预算，64..2000，可空
 */
public record MemoryRecallRequest(

    String query,

    @Min(value = 1, message = "topK 至少为 1")
    @Max(value = 30, message = "topK 最大为 30")
    Integer topK,

    @Min(value = 64, message = "token 预算至少为 64")
    @Max(value = 2000, message = "token 预算最大为 2000")
    Integer tokenBudget) {
}
