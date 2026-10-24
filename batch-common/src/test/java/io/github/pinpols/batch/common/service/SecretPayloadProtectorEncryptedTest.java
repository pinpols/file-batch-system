package io.github.pinpols.batch.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.config.BatchKmsProperties;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.utils.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Replay 配置保护要求真实认证密文")
class SecretPayloadProtectorEncryptedTest {

  private SecretPayloadProtector protector;

  @BeforeEach
  void setUp() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    String encodedKey = Base64.getEncoder().encodeToString(key);
    BatchKmsProperties kms = new BatchKmsProperties();
    kms.setDefaultKeyRef("replay-test-key");
    kms.setKeys(Map.of("replay-test-key", encodedKey));
    BatchSecurityProperties security = new BatchSecurityProperties();
    BatchObjectCryptoService crypto = new BatchObjectCryptoService(security, kms);
    protector = new SecretPayloadProtector(crypto);
  }

  @Test
  @DisplayName("随机测试密钥保护的配置能严格解密且信封不含明文")
  void shouldRevealAuthenticatedCiphertext_whenProtectedByPlatformKey() {
    String plaintext = "{\"configuration\":\"private-query-marker\"}";

    String protectedJson = protector.protect(plaintext);
    String recovered = protector.revealEncrypted(protectedJson);

    assertThat(recovered).isEqualTo(plaintext);
    assertThat(protectedJson).doesNotContain("private-query-marker");
  }

  @Test
  @DisplayName("伪造 Base64 明文信封不能成为可信 Replay 快照")
  void shouldRejectPlaintextEnvelope_whenCiphertextIsOnlyBase64Encoded() {
    byte[] plaintext = "{\"configuration\":\"forged\"}".getBytes(StandardCharsets.UTF_8);
    String encoded = Base64.getEncoder().encodeToString(plaintext);
    String forged = JsonUtils.toJson(Map.of("format", "BATCHENC_BASE64_V1", "ciphertext", encoded));

    assertThatThrownBy(() -> protector.revealEncrypted(forged))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authenticated ciphertext");
  }

  @Test
  @DisplayName("真实密文被改动后认证失败且不能退回明文")
  void shouldRejectModifiedCiphertext_whenAuthenticationFails() {
    String protectedJson = protector.protect("{\"configuration\":\"private-query-marker\"}");
    Map<String, Object> envelope =
        JsonUtils.fromJson(protectedJson, new TypeReference<Map<String, Object>>() {});
    byte[] ciphertext = Base64.getDecoder().decode((String) envelope.get("ciphertext"));
    ciphertext[ciphertext.length - 1] ^= 1;
    String modified = Base64.getEncoder().encodeToString(ciphertext);
    envelope.put("ciphertext", modified);
    String tampered = JsonUtils.toJson(envelope);

    assertThatThrownBy(() -> protector.revealEncrypted(tampered))
        .isInstanceOf(RuntimeException.class);
  }
}
