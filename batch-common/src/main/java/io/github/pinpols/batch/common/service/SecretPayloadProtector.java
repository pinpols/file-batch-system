package io.github.pinpols.batch.common.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/** 使用平台 KMS 密钥环保护结构化密钥载荷，供配置写入和历史数据迁移复用。 */
@RequiredArgsConstructor
public class SecretPayloadProtector {

  public static final String FORMAT = "BATCHENC_BASE64_V1";

  private final BatchObjectCryptoService cryptoService;

  public String protect(String plaintextJson) {
    if (!Texts.hasText(plaintextJson)) {
      throw new IllegalArgumentException("secretPayloadJson is required");
    }
    byte[] plaintext = plaintextJson.getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = cryptoService.encrypt(plaintext, null);
    String encodedPayload = Base64.getEncoder().encodeToString(encrypted);
    EncryptedSecretEnvelope envelope = new EncryptedSecretEnvelope(FORMAT, encodedPayload);
    return JsonUtils.toJson(envelope);
  }

  /** 解密受保护的密钥 JSON。仅供受信任的运行时适配器调用，调用方不得记录返回值。 */
  public String reveal(String protectedJson) {
    return reveal(protectedJson, false);
  }

  /** Replay 配置快照必须实际使用认证加密；不接受仅作 Base64 包装的明文。 */
  public String revealEncrypted(String protectedJson) {
    return reveal(protectedJson, true);
  }

  private String reveal(String protectedJson, boolean requireEncrypted) {
    if (!Texts.hasText(protectedJson)) {
      throw new IllegalArgumentException("protected secret payload is required");
    }
    Object parsed = JsonUtils.fromJson(protectedJson, new TypeReference<Object>() {});
    if (!(parsed instanceof Map<?, ?> envelope) || !FORMAT.equals(envelope.get("format"))) {
      throw new IllegalArgumentException("unsupported protected secret payload format");
    }
    Object ciphertext = envelope.get("ciphertext");
    if (!(ciphertext instanceof String encoded) || EmptyChecks.isBlank(encoded)) {
      throw new IllegalArgumentException("protected secret ciphertext is missing");
    }
    Base64.Decoder decoder = Base64.getDecoder();
    byte[] ciphertextBytes = decoder.decode(encoded);
    boolean requiredCiphertextIsNotEncrypted =
        requireEncrypted && !cryptoService.isEncryptedContent(ciphertextBytes);
    if (requiredCiphertextIsNotEncrypted) {
      throw new IllegalArgumentException("protected payload must contain authenticated ciphertext");
    }
    byte[] decrypted = cryptoService.decrypt(ciphertextBytes);
    return new String(decrypted, StandardCharsets.UTF_8);
  }

  record EncryptedSecretEnvelope(String format, String ciphertext) {}
}
