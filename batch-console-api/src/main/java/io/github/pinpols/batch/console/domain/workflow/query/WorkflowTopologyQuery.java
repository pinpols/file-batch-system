package io.github.pinpols.batch.console.domain.workflow.query;

public record WorkflowTopologyQuery(
    String tenantId, String workflowCode, Integer version, Long workflowRunId) {}
