package com.workbench.ai.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.workbench.ai.domain.AiConfig;
import com.workbench.ai.dto.AiConfigTestRequest;
import com.workbench.ai.dto.AiConfigView;
import com.workbench.ai.repository.AiConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class AiConfigServiceTest {

  private static final MockEnvironment ENV = new MockEnvironment()
      .withProperty("workbench.ai.base-url", "https://env.example/v1")
      .withProperty("workbench.ai.model", "env-model")
      .withProperty("workbench.ai.api-key", "env-key");

  /** 已配置口令的加密器：既有用例里的「db-key」是无 ENC 前缀的历史明文，decryptOrRaw 原样放行。 */
  private static final AiConfigCrypto CRYPTO = new AiConfigCrypto("test-crypto-key-abcdef");

  @Test
  void fallsBackToEnvWhenDatabaseRowIsAbsent() {
    AiConfigRepository repository = mock(AiConfigRepository.class);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

    AiConfigService.AiSettings settings = new AiConfigService(repository, ENV, CRYPTO).resolve();

    assertEquals("https://env.example/v1", settings.baseUrl());
    assertEquals("env-model", settings.model());
    assertEquals("env-key", settings.apiKey());
    assertEquals(AiConfigService.SOURCE_ENV, settings.source());
  }

  @Test
  void databaseConfigWinsOverEnv() {
    AiConfigRepository repository = mock(AiConfigRepository.class);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(AiConfig.builder()
        .baseUrl("https://db.example/v1")
        .model("db-model")
        .apiKey("db-key")
        .build()));

    AiConfigService service = new AiConfigService(repository, ENV, CRYPTO);
    AiConfigService.AiSettings settings = service.resolve();

    assertEquals("https://db.example/v1", settings.baseUrl());
    assertEquals("db-model", settings.model());
    assertEquals("db-key", settings.apiKey());
    assertEquals(AiConfigService.SOURCE_DB, settings.source());
  }

  @Test
  void viewMaskedApiKeyNeverLeaksPlainText() {
    AiConfigRepository repository = mock(AiConfigRepository.class);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(AiConfig.builder()
        .baseUrl("https://db.example/v1")
        .model("db-model")
        .apiKey("sk-abcdef123456")
        .build()));

    AiConfigView view = new AiConfigService(repository, ENV, CRYPTO).view();

    assertEquals(true, view.isApiKeyConfigured());
    // 规则：首尾各留 4 位，"sk-" 之外的头 1 位属于可展示信息，明文中间部分不外泄
    assertEquals("sk-a***3456", view.getApiKeyMasked());
    org.assertj.core.api.Assertions.assertThat(view.toString()).doesNotContain("abcdef123456");
  }

  @Test
  void saveKeepsExistingKeyWhenApiKeyIsBlank() {
    AiConfig existing = AiConfig.builder()
        .baseUrl("https://old.example/v1")
        .model("old-model")
        .apiKey("old-key")
        .build();
    AiConfigRepository repository = mock(AiConfigRepository.class);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(existing));
    when(repository.save(existing)).thenReturn(existing);

    AiConfig saved = new AiConfigService(repository, ENV, CRYPTO)
        .save("https://new.example/v1", "new-model", "", "admin");

    assertEquals("https://new.example/v1", saved.getBaseUrl());
    assertEquals("new-model", saved.getModel());
    assertEquals("old-key", saved.getApiKey(), "留空密钥时不得清空已保存的密钥");
    assertEquals("admin", saved.getUpdatedBy());
  }

  @Test
  void saveStoresCipherTextAndResolveDecryptsItBack() {
    AiConfig existing = AiConfig.builder()
        .baseUrl("https://old.example/v1")
        .model("old-model")
        .build();
    AiConfigRepository repository = mock(AiConfigRepository.class);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(existing));
    when(repository.save(existing)).thenReturn(existing);

    AiConfig saved = new AiConfigService(repository, ENV, CRYPTO)
        .save("https://new.example/v1", "new-model", "sk-secret-123", "admin");

    assertTrue(saved.getApiKey().startsWith("ENC("), "落库的必须是 ENC 密文，而不是明文");
    assertTrue(!saved.getApiKey().contains("sk-secret-123"), "库里不得出现明文密钥——D1 的验收标准");

    // 保存后的密文要能被下一次读取解回明文：模拟重启后另一实例从库里取
    AiConfigRepository reloaded = mock(AiConfigRepository.class);
    when(reloaded.findFirstByOrderByIdAsc()).thenReturn(Optional.of(AiConfig.builder()
        .baseUrl("https://new.example/v1")
        .model("new-model")
        .apiKey(saved.getApiKey())
        .build()));
    assertEquals("sk-secret-123", new AiConfigService(reloaded, ENV, CRYPTO).resolve().apiKey());
  }

  @Test
  void saveRejectsNewKeyWhenCryptoKeyNotConfigured() {
    AiConfig entity = AiConfig.builder()
        .baseUrl("https://db.example/v1")
        .model("m")
        .apiKey("legacy-plain")
        .build();
    AiConfigRepository repository = mock(AiConfigRepository.class);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(entity));
    when(repository.save(entity)).thenReturn(entity);
    AiConfigService service = new AiConfigService(repository, ENV, new AiConfigCrypto(""));

    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> service.save("https://db.example/v1", "m", "sk-new", "admin"));
    assertTrue(ex.getMessage().contains("WORKBENCH_AI_CRYPTO_KEY"), "报错必须指向要配的变量");

    // 留空密钥（保留旧值）不受影响：管理员改地址/模型名不必先配口令
    AiConfig kept = service.save("https://db.example/v1", "m2", "", "admin");
    assertEquals("legacy-plain", kept.getApiKey());
  }

  @Test
  void resolveStillReadsLegacyPlainTextWithoutCryptoKey() {
    AiConfigRepository repository = mock(AiConfigRepository.class);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(AiConfig.builder()
        .baseUrl("https://db.example/v1")
        .model("m")
        .apiKey("legacy-plain")
        .build()));

    // 升级后未配口令：历史明文照常可用，存量部署不会被这次修复打断
    assertEquals("legacy-plain",
        new AiConfigService(repository, ENV, new AiConfigCrypto("")).resolve().apiKey());
  }

  @Test
  void resolveFailsLoudlyOnEncryptedKeyWithoutCryptoKey() {
    AiConfigRepository repository = mock(AiConfigRepository.class);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(AiConfig.builder()
        .baseUrl("https://db.example/v1")
        .model("m")
        .apiKey("ENC(deadbeef)")
        .build()));

    // 库里有密文但口令丢了：显式报错，绝不把 ENC(...) 当明文发给上游换来一句 401
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> new AiConfigService(repository, ENV, new AiConfigCrypto("")).resolve());
    assertTrue(ex.getMessage().contains("WORKBENCH_AI_CRYPTO_KEY"));
  }

  @Test
  void rejectsMalformedBaseUrl() {
    AiConfigService service = new AiConfigService(mock(AiConfigRepository.class), ENV, CRYPTO);
    assertThrows(IllegalArgumentException.class,
        () -> service.save("ftp://bad.example", "m", "k", "admin"));
  }

  @Test
  void viewReportsNotConfiguredWhenEverySourceIsEmpty() {
    AiConfigRepository repository = mock(AiConfigRepository.class);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

    AiConfigView view = new AiConfigService(repository, new MockEnvironment(), CRYPTO).view();

    assertEquals(false, view.isApiKeyConfigured());
    assertNull(view.getApiKeyMasked());
  }

  @Test
  void testConnectionReportsOkOnValidChatCompletion() {
    RestTemplate probe = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(probe).build();
    server.expect(requestTo("https://db.example/v1/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.model").value("db-model"))
        .andExpect(jsonPath("$.max_tokens").value(1))
        .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}",
            MediaType.APPLICATION_JSON));

    AiConfigService.AiConnectionCheck check =
        new AiConfigService(mockDbRepository(), ENV, CRYPTO, probe)
            .testConnection(new AiConfigTestRequest(null, null, null));

    assertTrue(check.ok(), "choices 字段齐全才算真的通，光 200 不算");
    assertTrue(check.message().contains("db-model"));
    assertTrue(check.latencyMs() >= 0);
    server.verify();
  }

  @Test
  void testConnectionSurfacesUpstreamStatusAndReason() {
    RestTemplate probe = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(probe).build();
    server.expect(requestTo("https://db.example/v1/chat/completions"))
        .andRespond(withBadRequest()
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"message\":\"Invalid API key\"}}"));

    AiConfigService.AiConnectionCheck check =
        new AiConfigService(mockDbRepository(), ENV, CRYPTO, probe)
            .testConnection(new AiConfigTestRequest(null, null, null));

    assertEquals(false, check.ok());
    assertTrue(check.message().contains("HTTP 401") || check.message().contains("400"),
        "失败原因必须带上上游状态码，实际：" + check.message());
    assertTrue(check.message().contains("Invalid API key"));
  }

  @Test
  void testConnectionRejectsNonChatCompletionBodyEvenOn200() {
    RestTemplate probe = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(probe).build();
    server.expect(requestTo("https://db.example/v1/chat/completions"))
        .andRespond(withSuccess("{\"object\":\"list\"}", MediaType.APPLICATION_JSON));

    AiConfigService.AiConnectionCheck check =
        new AiConfigService(mockDbRepository(), ENV, CRYPTO, probe)
            .testConnection(new AiConfigTestRequest(null, null, null));

    assertEquals(false, check.ok(), "200 但没有 choices，说明不是 OpenAI 兼容接口，必须报失败");
  }

  @Test
  void testConnectionFallsBackToSavedValuesForBlankFields() {
    RestTemplate probe = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(probe).build();
    server.expect(requestTo("https://new.example/v1/chat/completions"))
        .andExpect(jsonPath("$.model").value("db-model"))
        .andExpect(header("Authorization", "Bearer db-key"))
        .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

    // 只改了 Base URL，模型名与密钥留空 —— 必须沿用库里已保存的，否则会被空 key 误判成 401
    AiConfigService.AiConnectionCheck check =
        new AiConfigService(mockDbRepository(), ENV, CRYPTO, probe)
            .testConnection(new AiConfigTestRequest("https://new.example/v1/", null, null));

    assertTrue(check.ok());
    server.verify();
  }

  @Test
  void testConnectionReportsFailureWhenNothingIsConfigured() {
    AiConfigRepository repository = mock(AiConfigRepository.class);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

    AiConfigService.AiConnectionCheck check =
        new AiConfigService(repository, new MockEnvironment(), CRYPTO, new RestTemplate())
            .testConnection(null);

    assertEquals(false, check.ok());
    assertTrue(check.message().contains("还没配"));
  }

  private static AiConfigRepository mockDbRepository() {
    AiConfigRepository repository = mock(AiConfigRepository.class);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(AiConfig.builder()
        .baseUrl("https://db.example/v1")
        .model("db-model")
        .apiKey("db-key")
        .build()));
    return repository;
  }
}
