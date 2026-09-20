package io.github.pinpols.batch.console.application.contract.request.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ConfigReleaseApprovalSubmitRequest {

  @NotBlank
  @Size(max = 64)
  private String tenantId;

  @Size(max = 512)
  private String reason;
}
