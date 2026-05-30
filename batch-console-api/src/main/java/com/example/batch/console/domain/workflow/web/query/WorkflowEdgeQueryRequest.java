package com.example.batch.console.domain.workflow.web.query;

import com.example.batch.console.web.query.PageQueryRequest;
import lombok.Data;

@Data
public class WorkflowEdgeQueryRequest extends PageQueryRequest {

  private String tenantId;
  private Long workflowDefinitionId;
  private String workflowCode;
  private String fromNodeCode;
  private String toNodeCode;
  private String edgeType;
  private Boolean enabled;
}
