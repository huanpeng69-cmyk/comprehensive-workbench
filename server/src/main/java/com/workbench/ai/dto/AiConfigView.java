package com.workbench.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 写作台「模型设置」回显给浏览器的配置。
 * 密钥一律脱敏：只告诉前端「有没有配置、尾部几位是什么」，明文永不外泄。
 *
 * @param baseUrl            OpenAI 兼容接口地址
 * @param model              模型名
 * @param apiKeyConfigured   是否已配置密钥（含环境变量来源）
 * @param apiKeyMasked       密钥脱敏展示，形如 sk-***abcd；未配置时为 null
 * @param source             当前生效来源：db（页面配置）或 env（环境变量）
 * @param editable           当前登录用户是否可修改配置。由后端按角色判定，
 *                           是唯一权威来源——前端不再维护会漂移的角色副本。
 */
@Getter
@AllArgsConstructor
public class AiConfigView {

  private String baseUrl;
  private String model;
  private boolean apiKeyConfigured;
  private String apiKeyMasked;
  private String source;
  private boolean editable;

  public static AiConfigView of(
      String baseUrl, String model, String apiKey, String source, boolean editable) {
    return new AiConfigView(
        baseUrl,
        model,
        apiKey != null && !apiKey.isBlank(),
        mask(apiKey),
        source,
        editable);
  }

  private static String mask(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      return null;
    }
    int keep = Math.min(4, apiKey.length());
    return apiKey.substring(0, keep) + "***" + apiKey.substring(apiKey.length() - keep);
  }
}
