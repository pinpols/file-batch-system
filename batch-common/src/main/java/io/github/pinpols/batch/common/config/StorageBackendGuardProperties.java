package io.github.pinpols.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 对象存储后端或位置发生变更时必须提供的一次性标记。 */
@Data
@ConfigurationProperties(prefix = "batch.storage.backend-guard")
public class StorageBackendGuardProperties {

  private String cutoverId = "";
}
