package com.example.batch.console.domain.workflow.support;

import com.example.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowNodeRunMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowRunMapper;
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
