package io.github.pinpols.batch.console.domain.workflow.application.command;

import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest;
import java.util.Objects;
import lombok.Builder;

/** 一次已保存定义的版本快照输入；图仍从调用事务中的持久化节点与边读取。 */
@Builder
public record WorkflowVersionSnapshotCommand(
    String tenantId,
    Long definitionId,
    String workflowCode,
    Integer version,
    WorkflowDefinitionSaveRequest body,
    Boolean scheduleEnabled,
    String savedBy) {

  public WorkflowVersionSnapshotCommand {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(definitionId, "definitionId");
    Objects.requireNonNull(workflowCode, "workflowCode");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(body, "body");
  }
}
