package com.workbench.memory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改记忆请求（PUT /api/memories/{id}），只允许改「用户能自己决定」的四个字段。
 *
 * <p><b>与新增的差别只有一处，但很关键：{@code ttlDays} 允许传 0</b>。</p>
 * <ul>
 *   <li>新增时 0 没有意义（本来就是「没有有效期」），所以 {@link MemoryCreateRequest} 限定 1..3650；</li>
 *   <li>修改时用户需要一条「把有效期去掉」的表达，null 又已经被占用为「这次不改有效期」，
 *       因此这里用 0 表示<b>清除有效期（改为永不过期）</b>。</li>
 * </ul>
 *
 * <p>其余字段为 null 一律表示「保持原值」，而不是「重置为默认值」——前端只改重要度时
 * 不必把内容原样回传两份语义。</p>
 *
 * @param content    记忆正文；必填（不允许把一条记忆改成空内容，要清空请直接删除），
 *                   长度上限与 {@code MemoryService.MAX_CONTENT_LENGTH} 保持一致
 * @param type       记忆类型，可空（保持原类型）；非法值报 400，不做静默兜底
 * @param importance 重要度 0..100，可空（保持原值）
 * @param ttlDays    有效期天数：null = 保持原有效期，0 = 清除有效期（永不过期），
 *                   1..3650 = 从当前时间起 N 天
 */
public record MemoryUpdateRequest(

    @NotBlank(message = "记忆内容不能为空")
    @Size(max = 1000, message = "记忆内容不能超过 1000 个字符")
    String content,

    String type,

    @Min(value = 0, message = "重要度不能小于 0")
    @Max(value = 100, message = "重要度不能大于 100")
    Integer importance,

    @Min(value = 0, message = "有效期不能为负数；传 0 表示清除有效期")
    @Max(value = 3650, message = "有效期最长 3650 天")
    Integer ttlDays) {
}
