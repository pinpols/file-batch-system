package io.github.pinpols.batch.orchestrator.domain.entity;

import io.github.pinpols.batch.common.i18n.AbstractLocalizedErrorEntity;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class EventDeliveryLogEntity extends AbstractLocalizedErrorEntity {

  private Long id;
  private String tenantId;
  private Long outboxEventId;
  private String eventType;
  private String eventKey;
  private String targetTopic;
  private String targetWorkerId;
  private String deliveryStatus;
  private Integer deliveryAttempt;
  private String deliverySummary;

  private String traceId;
  private Instant createdAt;
  private Instant updatedAt;
}
