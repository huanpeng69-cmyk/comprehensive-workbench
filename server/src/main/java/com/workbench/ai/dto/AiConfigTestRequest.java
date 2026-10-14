package com.workbench.ai.dto;

/**
 * 「测试连接」的候选配置。
 *
 * <p>三个字段都可空，空的按「沿用已保存值」补位，与 {@code AiConfigService.save} 的语义一致：
 * apiKey 留空表示沿用已有密钥，这样弹窗里只改了 Base URL 也能直接测（不会被空 key 误判成 401）。
 */
public record AiConfigTestRequest(String baseUrl, String model, String apiKey) {}
