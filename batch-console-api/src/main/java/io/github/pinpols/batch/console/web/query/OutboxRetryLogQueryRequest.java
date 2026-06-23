package io.github.pinpols.batch.console.web.query;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OutboxRetryLogQueryRequest extends PageQueryRequest {

  @ValidTenantId private String tenantId;

  @Size(max = 32, message = "retryStatus too long (max 32)")
  private String retryStatus;

  @Size(max = 512, message = "eventKey too long (max 512)")
  private String eventKey;
}
