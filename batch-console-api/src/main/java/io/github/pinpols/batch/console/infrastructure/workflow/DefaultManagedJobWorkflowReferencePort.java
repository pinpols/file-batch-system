package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.workflow.ManagedJobWorkflowReferencePort;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultManagedJobWorkflowReferencePort implements ManagedJobWorkflowReferencePort {

  private final WorkflowDefinitionMapper workflowDefinitionMapper;

  @Override
  public boolean isEnabledSingleJobWorkflow(
      String tenantId, Long workflowDefinitionId, Long jobDefinitionId) {
    if (EmptyChecks.isNull(workflowDefinitionId) || EmptyChecks.isNull(jobDefinitionId)) {
      return false;
    }
    WorkflowDefinitionEntity workflow =
        workflowDefinitionMapper.selectById(tenantId, workflowDefinitionId);
    return EmptyChecks.isNotNull(workflow)
        && Boolean.TRUE.equals(workflow.getEnabled())
        && jobDefinitionId.equals(workflow.getSingleJobDefinitionId());
  }
}
