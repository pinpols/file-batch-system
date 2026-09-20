package io.github.pinpols.batch.console.application.contract.request.config;

import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class ConfigReleaseActionRequest {

  private String tenantId;
  private String reason;

  @Positive
  private Integer expectedVersionNo;
}
