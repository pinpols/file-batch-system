package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record WorkflowEdgeQuery(
        String tenantId,
        Long workflowDefinitionId,
        String workflowCode,
        String fromNodeCode,
        String toNodeCode,
        String edgeType,
        Boolean enabled,
        PageRequest pageRequest
) {
}
