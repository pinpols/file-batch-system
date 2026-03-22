package com.example.batch.console.domain.query;

public record WorkflowEdgeQuery(
        String tenantId,
        Long workflowDefinitionId,
        String workflowCode,
        String fromNodeCode,
        String toNodeCode,
        String edgeType,
        Boolean enabled
) {
}
