package io.github.pinpols.batch.orchestrator.application.service.workflow;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;

@Component
@RequiredArgsConstructor
public class OrchestratorWorkflowMappers {

    public final WorkflowNodeMapper workflowNodeMapper;
    public final WorkflowRunMapper workflowRunMapper;
    public final WorkflowNodeRunMapper workflowNodeRunMapper;
}
