package com.example.batch.console.support.querymap;

import com.example.batch.console.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import com.example.batch.console.mapper.WorkflowNodeRunMapper;
import com.example.batch.console.mapper.WorkflowRunMapper;
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
