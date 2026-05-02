package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrchestratorWorkflowMappers {

  public final WorkflowNodeMapper workflowNodeMapper;
  public final WorkflowRunMapper workflowRunMapper;
  public final WorkflowNodeRunMapper workflowNodeRunMapper;
}
