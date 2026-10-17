package com.workbench.memory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增记忆请求（用户手写路径，source 固定为 MANUAL）。
 *
 * @param content    记忆正文；不能为空，长度上限与 {@code MemoryService.MAX_CONTENT_LENGTH} 保持一致
 * @param type       记忆类型，可空（默认 SEMANTIC）；非法值报 400，不做静默兜底
 * @param importance 重要度 0..100，可空（默认 50）
 * @param ttlDays    有效期天数 1..3650，可空表示永不过期
 */
public record MemoryCreateRequest(

    @NotBlank(message = "记忆内容不能为空")
    @Size(max = 1000, message = "记忆内容不能超过 1000 个字符")
    String content,

    String type,

    @Min(value = 0, message = "重要度不能小于 0")
    @Max(value = 100, message = "重要度不能大于 100")
    Integer importance,

    @Min(value = 1, message = "有效期至少 1 天")
    @Max(value = 3650, message = "有效期最长 3650 天")
    Integer ttlDays) {
}
