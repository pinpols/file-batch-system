package io.github.pinpols.batch.orchestrator.domain.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record WorkflowRunQuery(
    String tenantId, Long workflowDefinitionId, String runStatus, PageRequest pageRequest) {}
