package com.example.batch.orchestrator.domain.entity;

import lombok.Data;

import java.time.Instant;

@Data
public class WorkflowEdgeEntity {

    private Long id;
    private Long workflowDefinitionId;
    private String fromNodeCode;
    private String toNodeCode;
    private String edgeType;
    private String conditionExpr;
    private Boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
}
