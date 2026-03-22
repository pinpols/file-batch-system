package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class JobStepInstanceEntity {

    private Long id;
    private String tenantId;
    private Long jobInstanceId;
    private Long jobPartitionId;
    private Long jobTaskId;
    private String stepCode;
    private String stepType;
    private String stepStatus;
    private Integer retryCount;
    private Long relatedFileId;
    private String resultSummary;
    private String errorCode;
    private String errorMessage;
    private Long version;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
