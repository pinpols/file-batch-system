package io.github.pinpols.batch.console.domain.workflow.support;

import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConsoleWorkflowQueryMappers {

  public final WorkflowDefinitionMapper workflowDefinitionMapper;
  public final WorkflowNodeMapper workflowNodeMapper;
  public final WorkflowEdgeMapper workflowEdgeMapper;
  public final WorkflowRunMapper workflowRunMapper;
  public final WorkflowNodeRunMapper workflowNodeRunMapper;
}
