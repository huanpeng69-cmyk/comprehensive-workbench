package com.workbench.workflow.application.dto;

import jakarta.validation.constraints.NotBlank;

/** 转办请求：把任务转给另一用户处理。 */
public record TransmitRequest(
    @NotBlank String toUsername,
    String comment) {
}
