package io.github.pinpols.batch.console.domain.workflow.web.query;

import io.github.pinpols.batch.console.web.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class WorkflowNodeQueryRequest extends PageQueryRequest {

  private String tenantId;
  private Long workflowDefinitionId;
  private String workflowCode;
  private String nodeCode;
  private String nodeType;
  private Boolean enabled;
}
