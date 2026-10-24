package io.github.pinpols.batch.trigger.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class ExternalEventDeliveryEntity {

  private Long id;
  private String tenantId;
  private String eventSource;
  private String eventId;
  private String eventCode;
  private LocalDate bizDate;
  private String requestId;
  private String payloadSnapshot;
  private String targetWorkflowDefinitionIds;
  private String traceId;
  private Instant createdAt;
}
