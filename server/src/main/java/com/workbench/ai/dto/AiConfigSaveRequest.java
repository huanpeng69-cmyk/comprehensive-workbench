package com.workbench.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 写作台「模型设置」提交体。仅管理员可提交。
 * apiKey 允许为空字符串，表示该字段不修改（保留库里已有的密钥）。
 */
public record AiConfigSaveRequest(

    @NotBlank(message = "接口地址不能为空") String baseUrl,

    @NotBlank(message = "模型名不能为空") String model,

    String apiKey) {
}
