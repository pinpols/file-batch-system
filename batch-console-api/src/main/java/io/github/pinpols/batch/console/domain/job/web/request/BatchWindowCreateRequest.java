package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidResourceCode;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BatchWindowCreateRequest {
  @ValidTenantId private String tenantId;

  @ValidResourceCode private String windowCode;

  @Size(max = 256)
  private String windowName;

  @NotBlank
  @Size(max = 64)
  private String timezone;

  @NotBlank private String startTime;
  @NotBlank private String endTime;

  @Size(max = 32)
  private String endStrategy;

  @Size(max = 32)
  private String outOfWindowAction;

  private Boolean allowCrossDay;
  private Boolean enabled;

  @Size(max = 512)
  private String description;
}
