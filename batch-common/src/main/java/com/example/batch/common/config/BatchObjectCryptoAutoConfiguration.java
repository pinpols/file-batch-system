package com.example.batch.common.config;

import com.example.batch.common.service.BatchObjectCryptoService;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@EnableConfigurationProperties({BatchSecurityProperties.class, BatchKmsProperties.class})
public class BatchObjectCryptoAutoConfiguration {

  @Bean
  public BatchObjectCryptoService batchObjectCryptoService(
      BatchSecurityProperties securityProperties,
      BatchKmsProperties kmsProperties,
      Environment environment) {
    validateKmsKeysNotWeakInProd(kmsProperties, environment);
    return new BatchObjectCryptoService(securityProperties, kmsProperties);
  }

  /**
   * 生产 profile 下拒绝弱/占位 KMS 密钥(如 batch-defaults 的 {@code DEFAULT_TEST=AAAA...==} 全零密钥)。否则未注入 {@code
   * BATCH_SECURITY_KMS_KEYS_*} 时会用公开已知的全零密钥加密生产数据,密文可被直接解密。
   */
  static void validateKmsKeysNotWeakInProd(
      BatchKmsProperties kmsProperties, Environment environment) {
    if (!BatchProfileSupport.isProductionProfile(environment)) {
      return;
    }
    for (Map.Entry<String, String> entry : kmsProperties.getKeys().entrySet()) {
      if (isWeakKey(entry.getValue())) {
        throw new IllegalStateException(
            "FATAL: 生产环境 batch.security.kms.keys."
                + entry.getKey()
                + " 为弱/占位密钥(空或全零),请通过 BATCH_SECURITY_KMS_KEYS_* 环境变量注入真实密钥");
      }
    }
  }

  /** 弱密钥判定:空、非法 base64,或解码后全零字节。 */
  private static boolean isWeakKey(String value) {
    if (value == null || value.isBlank()) {
      return true;
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(value.trim());
    } catch (IllegalArgumentException ex) {
      return true; // 非法 base64 当弱密钥拒绝
    }
    if (decoded.length == 0) {
      return true;
    }
    for (byte b : decoded) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }
}
