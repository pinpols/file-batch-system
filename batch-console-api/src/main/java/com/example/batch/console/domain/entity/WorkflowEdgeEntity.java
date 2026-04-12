package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WorkflowEdgeEntity {

  private Long id;
  private Long workflowDefinitionId;
  private String fromNodeCode;
  private String toNodeCode;
  private String edgeType;
  private String conditionExpr;
  private Boolean enabled;
  private Instant createdAt;
  private Instant updatedAt;
}
