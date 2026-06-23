package io.github.pinpols.batch.console.domain.query;

import lombok.Data;

@Data
public class ConfigChangeLogQuery {

  private String tenantId;
  private String configType;
  private String configKey;
  private String changeAction;
}
