package com.example.batch.console.domain.notification.web.query;

import com.example.batch.common.validation.ValidTenantId;
import com.example.batch.console.web.query.PageQueryRequest;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AlertEventQueryRequest extends PageQueryRequest {

  @ValidTenantId private String tenantId;

  @Size(max = 16, message = "severity too long (max 16)")
  private String severity;

  @Size(max = 32, message = "status too long (max 32)")
  private String status;

  @Size(max = 64, message = "alertType too long (max 64)")
  private String alertType;

  @Size(max = 128, message = "traceId too long (max 128)")
  private String traceId;
}
