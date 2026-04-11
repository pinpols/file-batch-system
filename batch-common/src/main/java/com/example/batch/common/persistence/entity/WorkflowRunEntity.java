package com.example.batch.common.persistence.entity;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

@Data
public class WorkflowRunEntity {

    private Long id;
    private String tenantId;
    private Long workflowDefinitionId;
    private Long relatedJobInstanceId;
    private LocalDate bizDate;
    private String runStatus;
    private String currentNodeCode;
    private String traceId;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
