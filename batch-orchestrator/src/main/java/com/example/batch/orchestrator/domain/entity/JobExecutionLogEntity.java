package com.example.batch.orchestrator.domain.entity;

import lombok.Data;

import java.time.Instant;

@Data
public class JobExecutionLogEntity {

    private Long id;
    private String tenantId;
    private Long jobInstanceId;
    private Long jobPartitionId;
    private String logLevel;
    private String logType;
    private String traceId;
    private String message;
    private String detailRef;
    private String extraJson;
    private Instant createdAt;
}
