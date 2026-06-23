package io.github.pinpols.batch.trigger.web.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TriggerCatchUpRequest {

  @NotBlank private String tenantId;
  private String requestId;
  private Long pendingId;
  private String reason;
}
