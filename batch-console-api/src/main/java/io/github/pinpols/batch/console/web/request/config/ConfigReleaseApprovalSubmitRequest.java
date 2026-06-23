package io.github.pinpols.batch.console.web.request.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ConfigReleaseApprovalSubmitRequest {

  @NotBlank
  @Size(max = 64)
  private String tenantId;

  @NotBlank
  @Size(max = 64)
  private String operatorId;

  @Size(max = 512)
  private String reason;

  @Size(max = 64)
  private String expiredAt;
}
