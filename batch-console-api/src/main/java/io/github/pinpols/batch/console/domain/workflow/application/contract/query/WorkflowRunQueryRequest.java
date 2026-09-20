package io.github.pinpols.batch.console.domain.workflow.application.contract.query;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;
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
