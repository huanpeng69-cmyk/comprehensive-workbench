package com.workbench.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * AI 写作台模型配置（对应 ai_config 表，V9 迁移）。
 * 全表只保留一行：管理员在写作台「模型设置」里维护一次，全公司共用。
 * apiKey 只在服务端使用，接口返回时一律脱敏，不落到浏览器。
 */
@Entity
@Table(name = "ai_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConfig {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "base_url", length = 512)
  private String baseUrl;

  @Column(name = "model", length = 128)
  private String model;

  /** 密文 {@code ENC(<hex>)} 由 AiConfigCrypto 读写；历史明文兼容读、下次保存时加密回写。 */
  @Column(name = "api_key", columnDefinition = "text")
  private String apiKey;

  @Column(name = "updated_by", length = 64)
  private String updatedBy;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;
}
