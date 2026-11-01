package io.github.pinpols.batch.orchestrator.domain.view;

public record WorkflowDefinitionView(
    Long id,
    String tenantId,
    String workflowCode,
    String workflowName,
    String workflowType,
    Integer version,
    Boolean enabled,
    Long singleJobDefinitionId,
    String calendarCode) {}
