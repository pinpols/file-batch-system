package com.example.batch.console.domain.workflow.web.response;

import java.time.Instant;

public record ConsoleWorkflowEdgeResponse(
    Long id,
    Long workflowDefinitionId,
    String fromNodeCode,
    String toNodeCode,
    String edgeType,
    String conditionExpr,
    Boolean enabled,
    Instant createdAt,
    Instant updatedAt) {}
