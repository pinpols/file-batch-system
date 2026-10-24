package io.github.pinpols.batch.console.domain.workflow.application;

import io.github.pinpols.batch.console.domain.workflow.application.contract.request.ScheduledOccurrenceCatchUpRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowTriggerRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowTriggerStatusResponse;

/** Console 使用的顶层 Workflow 启动入口。 */
public interface ConsoleWorkflowTriggerService {

  String trigger(Long workflowDefinitionId, WorkflowTriggerRequest request, String idempotencyKey);

  WorkflowTriggerStatusResponse triggerStatus(
      Long workflowDefinitionId, String requestedTenantId, String idempotencyKey);

  ScheduledOccurrenceCatchUpResponse catchUpRejectedOccurrence(
      Long workflowDefinitionId,
      ScheduledOccurrenceCatchUpRequest request,
      String requestedBy,
      String idempotencyKey);
}
