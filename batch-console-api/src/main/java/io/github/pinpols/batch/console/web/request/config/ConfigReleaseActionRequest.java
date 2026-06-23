package io.github.pinpols.batch.console.web.request.config;

import lombok.Data;

@Data
public class ConfigReleaseActionRequest {

  private String tenantId;
  private String operatorId;
  private String traceId;
  private String reason;
  private String grayScopeJson;
}
