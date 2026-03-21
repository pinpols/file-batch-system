package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class JobTaskEntity {

    private Long id;
    private String tenantId;
    private Long jobInstanceId;
    private Long jobPartitionId;
    private String taskType;
    private Integer taskSeq;
    private String taskStatus;
    private String assignedWorkerCode;
    private String taskPayload;
    private String resultSummary;
    private String errorCode;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
