package com.example.batch.trigger.web.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TriggerCatchUpRequest {

  @NotBlank private String tenantId;
  @NotBlank private String requestId;
  private String reason;
}
