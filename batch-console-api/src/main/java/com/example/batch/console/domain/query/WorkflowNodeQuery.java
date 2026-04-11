package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record WorkflowNodeQuery(
        String tenantId,
        Long workflowDefinitionId,
        String workflowCode,
        String nodeCode,
        String nodeType,
        Boolean enabled,
        PageRequest pageRequest) {}
