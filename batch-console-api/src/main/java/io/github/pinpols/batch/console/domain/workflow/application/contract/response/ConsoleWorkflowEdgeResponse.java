package io.github.pinpols.batch.console.domain.workflow.application.contract.response;

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
