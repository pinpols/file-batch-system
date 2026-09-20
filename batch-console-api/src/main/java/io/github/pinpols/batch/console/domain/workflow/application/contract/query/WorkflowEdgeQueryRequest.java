package io.github.pinpols.batch.console.domain.workflow.application.contract.query;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
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
