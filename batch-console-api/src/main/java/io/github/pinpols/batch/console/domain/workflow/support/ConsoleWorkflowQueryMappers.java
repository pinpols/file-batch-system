package io.github.pinpols.batch.console.domain.workflow.support;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowRunMapper;

@Component
@RequiredArgsConstructor
public class ConsoleWorkflowQueryMappers {

    public final WorkflowDefinitionMapper workflowDefinitionMapper;
    public final WorkflowNodeMapper workflowNodeMapper;
    public final WorkflowEdgeMapper workflowEdgeMapper;
    public final WorkflowRunMapper workflowRunMapper;
    public final WorkflowNodeRunMapper workflowNodeRunMapper;
}
