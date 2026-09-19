package io.github.pinpols.batch.console.web.request.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class ConfigReleaseActionRequest {

  private String tenantId;
  private String operatorId;
  private String traceId;
  private String reason;
  private String grayScopeJson;

  @NotNull
  @Positive
  private Integer expectedVersionNo;
}
