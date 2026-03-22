package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class EventDeliveryLogEntity {

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
    private String errorMessage;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;
}
