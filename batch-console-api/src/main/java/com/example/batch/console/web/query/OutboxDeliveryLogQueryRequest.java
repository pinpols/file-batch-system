package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OutboxDeliveryLogQueryRequest extends PageQueryRequest {

  @ValidTenantId private String tenantId;

  @Size(max = 32, message = "deliveryStatus too long (max 32)")
  private String deliveryStatus;

  @Size(max = 64, message = "eventType too long (max 64)")
  private String eventType;

  @Size(max = 512, message = "eventKey too long (max 512)")
  private String eventKey;
}
