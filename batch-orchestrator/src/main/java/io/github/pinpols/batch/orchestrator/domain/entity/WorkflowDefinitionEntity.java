package io.github.pinpols.batch.orchestrator.domain.entity;

public record WorkflowDefinitionEntity(
    Long id,
    String tenantId,
    String workflowCode,
    String workflowName,
    String workflowType,
    Integer version,
    Boolean enabled) {}
