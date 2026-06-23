package io.github.pinpols.batch.console.domain.job.web.query;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BatchDayWindowQueryRequest {

  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128, message = "calendarCode too long (max 128)")
  private String calendarCode;
}
