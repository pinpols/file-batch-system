package io.github.pinpols.batch.console.infrastructure.workflow;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.console.application.workflow.ManagedJobWorkflowReferencePort;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;

@Component
@RequiredArgsConstructor
public class DefaultManagedJobWorkflowReferencePort implements ManagedJobWorkflowReferencePort {

    private final WorkflowDefinitionMapper workflowDefinitionMapper;

    @Override
    public boolean isEnabledSingleJobWorkflow(String tenantId, Long workflowDefinitionId, Long jobDefinitionId) {
        if (workflowDefinitionId == null || jobDefinitionId == null) {
            return false;
        }
        WorkflowDefinitionEntity workflow = workflowDefinitionMapper.selectById(tenantId, workflowDefinitionId);
        return workflow != null
                && Boolean.TRUE.equals(workflow.getEnabled())
                && jobDefinitionId.equals(workflow.getSingleJobDefinitionId());
    }
}
