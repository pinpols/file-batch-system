package io.github.pinpols.batch.console.domain.workflow.param;

import lombok.Data;

@Data
public class WorkflowEdgeUpsertParam {

  private String tenantId;
  private Long workflowDefinitionId;
  private String fromNodeCode;
  private String toNodeCode;
  private String edgeType;
  private String conditionExpr;
  private Boolean enabled;
}
