package com.example.batch.console.domain.query;

public record WorkflowDefinitionQuery(
        String tenantId,
        String workflowCode,
        String workflowType,
        Integer version,
        Boolean enabled
) {
}
