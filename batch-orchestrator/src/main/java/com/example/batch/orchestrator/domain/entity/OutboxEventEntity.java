package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class OutboxEventEntity {

    private Long id;
    private String tenantId;
    private String aggregateType;
    private Long aggregateId;
    private String eventType;
    private String eventKey;
    private String payloadJson;
    private String publishStatus;
    private Integer publishAttempt;
    private Instant nextPublishAt;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;
}
