package com.example.batch.console.mapper.param;

import lombok.Data;

@Data
public class WorkflowEdgeUpsertParam {

  private Long workflowDefinitionId;
  private String fromNodeCode;
  private String toNodeCode;
  private String edgeType;
  private String conditionExpr;
  private Boolean enabled;
}
