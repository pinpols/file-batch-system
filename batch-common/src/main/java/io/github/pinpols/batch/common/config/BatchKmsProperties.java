package io.github.pinpols.batch.common.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.security.kms")
/** KMS 集成的连接与密钥引用配置。 */
public class BatchKmsProperties {

  private String defaultKeyRef = "DEFAULT_TEST";
  private Map<String, String> keys = new LinkedHashMap<>();

  public void setKeys(Map<String, String> keys) {
    this.keys = keys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(keys);
  }
}
