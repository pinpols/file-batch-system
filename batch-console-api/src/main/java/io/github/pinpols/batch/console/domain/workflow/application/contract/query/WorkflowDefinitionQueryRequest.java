package io.github.pinpols.batch.console.domain.workflow.application.contract.query;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class WorkflowDefinitionQueryRequest extends PageQueryRequest {

  private String tenantId;
  private String workflowCode;
  private String workflowName;
  private String workflowType;
  private Integer version;
  private Boolean enabled = true;
}
