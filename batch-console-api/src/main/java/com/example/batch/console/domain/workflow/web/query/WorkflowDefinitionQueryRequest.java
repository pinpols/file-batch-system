package com.example.batch.console.domain.workflow.web.query;

import com.example.batch.console.web.query.PageQueryRequest;
import lombok.Data;

@Data
public class WorkflowDefinitionQueryRequest extends PageQueryRequest {

  private String tenantId;
  private String workflowCode;
  private String workflowName;
  private String workflowType;
  private Integer version;
  private Boolean enabled = true;
}
