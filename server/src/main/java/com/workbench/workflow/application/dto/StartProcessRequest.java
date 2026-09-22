package com.workbench.workflow.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 发起流程请求。variables 供条件路由使用（如 level、amount）。
 */
public record StartProcessRequest(
    @NotBlank String bizType,
    Long bizId,
    @NotBlank String title,
    Map<String, Object> variables) {
}
