package io.github.pinpols.batch.console.domain.workflow.web.query;

import lombok.Data;

@Data
public class WorkflowTopologyQueryRequest {

  private String tenantId;
  private String workflowCode;
  private Integer version;
  private Long workflowRunId;
}
