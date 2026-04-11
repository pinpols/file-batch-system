package com.example.batch.console.domain.entity;

import lombok.Data;

import java.time.Instant;

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
    private Instant startedAt;
    private Instant finishedAt;
}
