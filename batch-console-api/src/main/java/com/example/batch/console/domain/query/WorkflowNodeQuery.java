package com.example.batch.console.domain.query;

public record WorkflowNodeQuery(
        String tenantId,
        Long workflowDefinitionId,
        String workflowCode,
        String nodeCode,
        String nodeType,
        Boolean enabled
) {
}
