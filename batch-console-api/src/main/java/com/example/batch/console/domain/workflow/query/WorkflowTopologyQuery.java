package com.example.batch.console.domain.workflow.query;

public record WorkflowTopologyQuery(
    String tenantId, String workflowCode, Integer version, Long workflowRunId) {}
