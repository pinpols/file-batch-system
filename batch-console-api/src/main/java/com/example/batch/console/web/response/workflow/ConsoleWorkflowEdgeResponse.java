package com.example.batch.console.web.response.workflow;

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
