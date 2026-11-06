package com.workbench.ai.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link AiConfigCrypto} 的单元测试：加密往返、格式约定、口令缺失/不符时的显式报错，
 * 以及「历史明文兼容读」这一迁移语义（存量部署升级不破）。
 */
class AiConfigCryptoTest {

  private static final String KEY = "unit-test-crypto-key-0123456789";

  @Test
  void roundTripsPlainTextThroughEncFormat() {
    AiConfigCrypto crypto = new AiConfigCrypto(KEY);
    String cipher = crypto.encrypt("sk-live-abcdef123456");

    assertTrue(cipher.startsWith("ENC("), "密文必须带 ENC 前缀，读取侧靠它区分明文与密文");
    assertTrue(cipher.endsWith(")"));
    assertTrue(cipher.length() > "sk-live-abcdef123456".length(), "密文应比明文长（IV+GCM tag 的 hex）");
    assertEquals("sk-live-abcdef123456", crypto.decryptOrRaw(cipher));
  }

  @Test
  void encryptIsNonDeterministicSoCipherTextsDiffer() {
    AiConfigCrypto crypto = new AiConfigCrypto(KEY);
    // GCM 随机 IV：同一明文两次加密的密文必须不同，否则等于可被比对的固定映射
    assertNotEquals(crypto.encrypt("same-secret"), crypto.encrypt("same-secret"));
    assertEquals("same-secret", crypto.decryptOrRaw(crypto.encrypt("same-secret")));
  }

  @Test
  void cipherTextNeverContainsPlainText() {
    AiConfigCrypto crypto = new AiConfigCrypto(KEY);
    String cipher = crypto.encrypt("sk-plain-secret-987654321");
    assertTrue(!cipher.contains("sk-plain-secret-987654321"),
        "密文里不得出现明文片段——这是 D1 的全部意义");
  }

  @Test
  void unconfiguredCryptoRefusesToEncryptButKeepsLegacyReads() {
    AiConfigCrypto crypto = new AiConfigCrypto("");

    assertEquals(false, crypto.isConfigured());
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> crypto.encrypt("sk-new"));
    assertTrue(ex.getMessage().contains("WORKBENCH_AI_CRYPTO_KEY"), "报错必须告诉管理员去哪配");
    // 历史明文（无 ENC 前缀）必须原样放行，存量部署升级后才不会断
    assertEquals("legacy-plain-key", crypto.decryptOrRaw("legacy-plain-key"));
    assertNull(crypto.decryptOrRaw(null));
  }

  @Test
  void unconfiguredCryptoFailsLoudlyOnEncryptedValue() {
    AiConfigCrypto crypto = new AiConfigCrypto("");
    // 库里已有密文但口令丢了：显式报错，绝不把 ENC(...) 当明文发给上游
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> crypto.decryptOrRaw("ENC(deadbeef)"));
    assertTrue(ex.getMessage().contains("WORKBENCH_AI_CRYPTO_KEY"));
  }

  @Test
  void wrongKeyFailsLoudlyInsteadOfReturningGarbage() {
    String cipher = new AiConfigCrypto("key-A").encrypt("sk-under-key-A");
    AiConfigCrypto cryptoWithOtherKey = new AiConfigCrypto("key-B");

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> cryptoWithOtherKey.decryptOrRaw(cipher));
    assertTrue(ex.getMessage().contains("不一致"), "报错必须指向「口令变了」这个根因");
  }

  @Test
  void malformedEncValueFailsLoudly() {
    AiConfigCrypto crypto = new AiConfigCrypto(KEY);
    // 不是合法 hex 的密文：损坏数据要报错，不能静默吞掉
    assertThrows(IllegalStateException.class, () -> crypto.decryptOrRaw("ENC(not-hex!!)"));
  }
}
