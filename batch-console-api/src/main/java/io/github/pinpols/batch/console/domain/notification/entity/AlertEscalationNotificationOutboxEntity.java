package io.github.pinpols.batch.console.domain.notification.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class AlertEscalationNotificationOutboxEntity {

  private Long id;
  private String tenantId;
  private Long alertEventId;
  private Integer escalationTier;
  private String stream;
  private String eventType;
  private String payloadJson;
  private String publishStatus;
  private Integer attemptCount;
  private Instant nextPublishAt;
  private String lastError;
  private Instant createdAt;
  private Instant updatedAt;
}
