package io.github.pinpols.batch.console.domain.notification.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WebhookDeliveryLogEntity {

  private Long id;
  private String tenantId;
  private Long subscriptionId;
  private String eventType;
  private String payloadJson;
  private Integer httpStatus;
  private String responseBody;
  private String deliveryStatus;
  private Integer attempt;
  private Instant nextRetryAt;
  private Instant createdAt;
}
