package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record WorkflowDefinitionQuery(
        String tenantId,
        String workflowCode,
        String workflowName,
        String workflowType,
        Integer version,
        Boolean enabled,
        PageRequest pageRequest
) {
}
