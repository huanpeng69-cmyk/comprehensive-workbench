package com.workbench.ai.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * ai_config.api_key 的加密存储。
 *
 * <p>密文格式 {@code ENC(<hex>)}（AES-256-GCM）。读取时带前缀才解密，不带前缀视为修复前的
 * 历史明文原样放行——管理员下次保存配置时自动加密回写，避免「启动即批量改数据」的
 * 不可逆动作，存量部署因此无需停机迁移。
 *
 * <p>加密口令来自 {@code workbench.ai.crypto-key}（环境变量 WORKBENCH_AI_CRYPTO_KEY）。
 * 未配置时：<b>历史明文照常可读，但保存新密钥会被拒绝</b>。绝不静默降级回明文落库，
 * 那等于没修；也绝不把解不开的密文当明文发给上游，那只会换来一句费解的 401。
 *
 * <p>盐由口令派生（SHA-256 的前 16 个十六进制字符）而不是写死常量：TextEncryptor 的盐
 * 本就不要求保密，但公开仓库里人人可用的固定盐会削弱对弱口令的字典攻击防护；
 * 派生盐不增加任何配置负担，管理员只设一个变量。
 */
@Component
public class AiConfigCrypto {

  static final String PREFIX = "ENC(";
  private static final String SUFFIX = ")";

  private final TextEncryptor encryptor;

  /**
   * 生产构造器：口令由环境变量注入。
   *
   * <p><b>必须显式标 @Autowired</b>：本类同时有「Environment 生产构造器」和「String 测试构造器」，
   * 不标注解时 Spring 的构造器推断返回空、退回无参构造并抛
   * {@code No default constructor found}——单测抓不到（不装配容器），只有真起 jar 才暴露。
   * 同样的坑在 {@link AiConfigService} 上踩过一次，注释里有记录。
   */
  @Autowired
  public AiConfigCrypto(Environment environment) {
    this(environment.getProperty("workbench.ai.crypto-key", ""));
  }

  /** 包级构造器供测试直接给口令。 */
  AiConfigCrypto(String cryptoKey) {
    String trimmed = cryptoKey == null ? "" : cryptoKey.trim();
    this.encryptor = trimmed.isEmpty()
        ? null
        : Encryptors.delux(trimmed, derivedSalt(trimmed));
  }

  /** 是否已配置加密口令。未配置时只读不写：历史明文可读，新密钥拒存。 */
  boolean isConfigured() {
    return encryptor != null;
  }

  /** 明文 → {@code ENC(<hex>)}。未配置口令时显式拒绝，绝不静默存明文。 */
  String encrypt(String plainText) {
    if (plainText == null) {
      return null;
    }
    if (encryptor == null) {
      throw new IllegalStateException(
          "尚未配置 AI 密钥加密口令，拒绝以明文落库。请在 server/.env 增加一行"
              + " WORKBENCH_AI_CRYPTO_KEY=<任意长随机串>，重启后端后重试");
    }
    return PREFIX + encryptor.encrypt(plainText) + SUFFIX;
  }

  /**
   * 密文/历史明文统一出口：带 ENC 前缀才解密，否则视为历史明文原样返回。
   *
   * <p>带 ENC 前缀但口令缺失或解密失败时显式报错（口令与加密时不一致 / 密文损坏），
   * 让管理员知道去修哪里，而不是让上游返回一句无法定位的「Invalid API key」。
   */
  String decryptOrRaw(String stored) {
    if (stored == null || !stored.startsWith(PREFIX) || !stored.endsWith(SUFFIX)) {
      return stored;
    }
    if (encryptor == null) {
      throw new IllegalStateException(
          "数据库中的 AI 密钥已加密，但 WORKBENCH_AI_CRYPTO_KEY 未配置，无法解密使用。"
              + "请在 server/.env 恢复该变量后重启后端");
    }
    String cipherHex = stored.substring(PREFIX.length(), stored.length() - SUFFIX.length());
    try {
      return encryptor.decrypt(cipherHex);
    } catch (RuntimeException ex) {
      throw new IllegalStateException(
          "AI 密钥解密失败：WORKBENCH_AI_CRYPTO_KEY 与加密时不一致，或密文已损坏。"
              + "请用加密时的口令恢复该变量，或清空模型设置里的密钥后重新保存");
    }
  }

  /** 盐不保密，但取口令哈希而不是固定常量，避免公开仓库里出现人人可用的盐。 */
  private static String derivedSalt(String cryptoKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(("workbench-ai-salt:" + cryptoKey).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.substring(0, 16);
    } catch (NoSuchAlgorithmException ex) {
      // 主流 JDK 全部内置 SHA-256，到不了这里；写出来只是让编译器安心
      throw new IllegalStateException("SHA-256 不可用", ex);
    }
  }
}
