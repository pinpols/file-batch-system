package com.example.batch.console.domain.workflow.web.query;

import com.example.batch.console.web.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class WorkflowRunQueryRequest extends PageQueryRequest {

  private String tenantId;
  private Long workflowDefinitionId;
  private Long relatedJobInstanceId;
  private String runStatus;
  private String currentNodeCode;
  private String traceId;
}
