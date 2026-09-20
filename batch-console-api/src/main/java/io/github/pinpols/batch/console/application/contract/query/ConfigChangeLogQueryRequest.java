package io.github.pinpols.batch.console.application.contract.query;

import lombok.Data;

@Data
public class ConfigChangeLogQueryRequest {

  private String tenantId;
  private String configType;
  private String configKey;
  private String changeAction;
}
