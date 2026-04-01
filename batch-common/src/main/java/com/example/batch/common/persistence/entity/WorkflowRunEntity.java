package com.example.batch.common.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

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
