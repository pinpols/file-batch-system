package com.example.batch.orchestrator.domain.query;

import com.example.batch.common.model.PageRequest;

public record WorkflowRunQuery(
        String tenantId,
        Long workflowDefinitionId,
        String runStatus,
        PageRequest pageRequest
) {
}
