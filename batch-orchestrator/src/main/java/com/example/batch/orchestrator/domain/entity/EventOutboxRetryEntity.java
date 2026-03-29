package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class EventOutboxRetryEntity {

    private Long id;
    private String tenantId;
    private Long outboxEventId;
    private String eventKey;
    /**
     * Publish attempt sequence for the outbox event.
     *
     * <p>This is distinct from business retry counters on job/runtime entities.
     */
    private Integer publishAttempt;
    private String retryStatus;
    private String retryReason;
    private Instant nextRetryAt;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;
}
