package com.example.batch.orchestrator.domain.entity;

import lombok.Data;

import java.time.Instant;

@Data
public class RetryScheduleEntity {

    private Long id;
    private String tenantId;
    private String relatedType;
    private Long relatedId;
    private String retryPolicy;
    private Integer retryCount;
    private Integer maxRetryCount;
    private Instant nextRetryAt;
    private String retryStatus;
    private String dedupKey;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
