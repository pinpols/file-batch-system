package io.github.pinpols.batch.orchestrator.domain.entity;

public record WorkflowDefinitionEntity(
    Long id,
    String tenantId,
    String workflowCode,
    String workflowName,
    String workflowType,
    Integer version,
    Boolean enabled,
    Long singleJobDefinitionId,
    String calendarCode) {

  /** Compatibility constructor for legacy workflow definitions without a managed Job relation. */
  public WorkflowDefinitionEntity(
      Long id,
      String tenantId,
      String workflowCode,
      String workflowName,
      String workflowType,
      Integer version,
      Boolean enabled) {
    this(id, tenantId, workflowCode, workflowName, workflowType, version, enabled, null, null);
  }

  /** Compatibility constructor for callers predating Workflow-owned calendar configuration. */
  public WorkflowDefinitionEntity(
      Long id,
      String tenantId,
      String workflowCode,
      String workflowName,
      String workflowType,
      Integer version,
      Boolean enabled,
      Long singleJobDefinitionId) {
    this(
        id,
        tenantId,
        workflowCode,
        workflowName,
        workflowType,
        version,
        enabled,
        singleJobDefinitionId,
        null);
  }
}
