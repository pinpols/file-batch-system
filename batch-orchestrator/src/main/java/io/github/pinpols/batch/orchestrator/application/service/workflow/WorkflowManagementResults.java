package io.github.pinpols.batch.orchestrator.application.service.workflow;

/** 工作流运维操作的固定响应契约。 */
public final class WorkflowManagementResults {

  private WorkflowManagementResults() {}

  public record RunAction(Long id, String status) {}

  public record NodeAction(Long id, String nodeCode, String nodeStatus) {}
}
