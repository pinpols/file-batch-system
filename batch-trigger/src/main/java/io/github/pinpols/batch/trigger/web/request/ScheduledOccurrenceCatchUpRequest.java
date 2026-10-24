package io.github.pinpols.batch.trigger.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/** Explicit operator action for one rejected scheduled Workflow fire. */
@Data
public class ScheduledOccurrenceCatchUpRequest {

  @ValidTenantId
  private String tenantId;

  @NotNull
  @Positive
  private Long workflowDefinitionId;

  @NotNull
  private Instant scheduledFireTime;

  @NotNull
  private LocalDate bizDate;

  @NotBlank
  @Size(max = 128)
  private String requestedBy;

  @NotBlank
  @Size(max = 1024)
  private String reason;
}
