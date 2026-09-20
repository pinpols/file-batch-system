package io.github.pinpols.batch.common.service;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 使用平台 KMS 密钥环保护结构化密钥载荷，供配置写入和历史数据迁移复用。 */
@Component
@RequiredArgsConstructor
public class SecretPayloadProtector {

  public static final String FORMAT = "BATCHENC_BASE64_V1";

  private final BatchObjectCryptoService cryptoService;

  public String protect(String plaintextJson) {
    if (!Texts.hasText(plaintextJson)) {
      throw new IllegalArgumentException("secretPayloadJson is required");
    }
    byte[] encrypted = cryptoService.encrypt(plaintextJson.getBytes(StandardCharsets.UTF_8), null);
    return JsonUtils.toJson(
        new EncryptedSecretEnvelope(FORMAT, Base64.getEncoder().encodeToString(encrypted)));
  }

  record EncryptedSecretEnvelope(String format, String ciphertext) {}
}
