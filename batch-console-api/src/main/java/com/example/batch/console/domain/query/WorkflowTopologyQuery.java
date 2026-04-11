package com.example.batch.console.domain.query;

public record WorkflowTopologyQuery(
        String tenantId, String workflowCode, Integer version, Long workflowRunId) {}
