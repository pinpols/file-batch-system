package com.example.batch.common.persistence.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class AlertEventEntity {

  private Long id;
  private String tenantId;
  private String serviceName;
  private String alertType;
  private String severity;
  private String title;
  private String detailJson;
  private String dedupFingerprint;
  private Integer occurrenceCount;
  private Instant firstSeenAt;
  private Instant lastSeenAt;
  private String traceId;
  private String status;
  private Integer escalationTier;
  private Instant escalatedAt;
  private Integer escalationNotifiedTier;
  private Instant createdAt;
  private Instant updatedAt;
}
