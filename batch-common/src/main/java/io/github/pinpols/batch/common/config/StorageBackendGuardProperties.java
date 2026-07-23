package io.github.pinpols.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** One-time marker required when the object storage backend or location changes. */
@Data
@ConfigurationProperties(prefix = "batch.storage.backend-guard")
public class StorageBackendGuardProperties {

  private String cutoverId = "";
}
