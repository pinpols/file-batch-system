package io.github.pinpols.batch.common.config;

import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 按配置装配对象存储加解密能力。 */
@AutoConfiguration
@EnableConfigurationProperties({BatchSecurityProperties.class, BatchKmsProperties.class})
public class BatchObjectCryptoAutoConfiguration {

  private static final Set<Integer> AES_KEY_LENGTHS = Set.of(16, 24, 32);

  @Bean
  public BatchObjectCryptoService batchObjectCryptoService(
      BatchSecurityProperties securityProperties,
      BatchKmsProperties kmsProperties,
      Environment environment) {
    validateKmsKeys(kmsProperties, environment);
    return new BatchObjectCryptoService(securityProperties, kmsProperties);
  }

  @Bean
  @ConditionalOnMissingBean
  public SecretPayloadProtector secretPayloadProtector(
      BatchObjectCryptoService batchObjectCryptoService) {
    return new SecretPayloadProtector(batchObjectCryptoService);
  }

  /**
   * 启动期校验 KMS 密钥配置。所有环境都必须保证默认 key 引用存在、密钥能 base64 解码且长度符合 AES 要求；生产 profile 额外拒绝弱/占位密钥(如
   * batch-defaults 的 {@code DEFAULT_TEST=AAAA...==} 全零密钥)。否则未注入 {@code BATCH_SECURITY_KMS_KEYS_*} 时会用公开已知密钥加密生产数据。
   */
  static void validateKmsKeys(BatchKmsProperties kmsProperties, Environment environment) {
    Map<String, String> keys = kmsProperties.getKeys();
    String defaultKeyRef = kmsProperties.getDefaultKeyRef();
    if (EmptyChecks.isBlank(defaultKeyRef) || !keys.containsKey(defaultKeyRef)) {
      throw new IllegalStateException(
          "FATAL: batch.security.kms.default-key-ref must reference an existing batch.security.kms.keys entry");
    }

    boolean production = BatchProfileSupport.isProductionProfile(environment);
    for (Map.Entry<String, String> entry : keys.entrySet()) {
      byte[] decoded = decodeKmsKey(entry.getKey(), entry.getValue());
      if (!AES_KEY_LENGTHS.contains(decoded.length)) {
        throw new IllegalStateException("FATAL: batch.security.kms.keys."
            + entry.getKey()
            + " must decode to a 16, 24, or 32 byte AES key");
      }
      if (production && isAllZero(decoded)) {
        throw new IllegalStateException(
            "FATAL: production batch.security.kms.keys."
                + entry.getKey()
                + " is weak or a placeholder (empty or all-zero); inject a real key through the BATCH_SECURITY_KMS_KEYS_* environment variable");
      }
    }
  }

  private static byte[] decodeKmsKey(String keyRef, String value) {
    if (EmptyChecks.isBlank(value)) {
      throw new IllegalStateException(
          "FATAL: batch.security.kms.keys." + keyRef + " must not be blank");
    }
    try {
      return Base64.getDecoder().decode(value.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "FATAL: batch.security.kms.keys." + keyRef + " must be valid base64", ex);
    }
  }

  /** 弱密钥判定:解码后全零字节。 */
  private static boolean isAllZero(byte[] decoded) {
    for (byte b : decoded) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }
}
