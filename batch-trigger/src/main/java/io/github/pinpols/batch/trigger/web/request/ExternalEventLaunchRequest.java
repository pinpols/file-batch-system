package io.github.pinpols.batch.trigger.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.Data;

@Data
public class ExternalEventLaunchRequest {

  @ValidTenantId
  private String tenantId;

  @NotBlank
  @Size(max = 128)
  private String externalEventCode;

  @NotBlank
  @Size(max = 256)
  private String externalEventId;

  @NotNull
  private LocalDate bizDate;

  private Map<String, Object> params;

  private Instant dataIntervalStart;

  private Instant dataIntervalEnd;

  private Boolean dryRun;
}
