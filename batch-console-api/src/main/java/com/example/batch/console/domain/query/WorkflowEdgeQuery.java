package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record WorkflowEdgeQuery(
    String tenantId,
    Long workflowDefinitionId,
    String workflowCode,
    String fromNodeCode,
    String toNodeCode,
    String edgeType,
    Boolean enabled,
    PageRequest pageRequest) {

  /** 按 workflowDefinitionId 查询全部边，不带其他过滤条件。 */
  public static WorkflowEdgeQuery ofDefinition(Long workflowDefinitionId, PageRequest pageRequest) {
    return new WorkflowEdgeQuery(
        null, workflowDefinitionId, null, null, null, null, null, pageRequest);
  }

  /** 按租户 + workflowDefinitionId 查询全部边。 */
  public static WorkflowEdgeQuery ofDefinition(
      String tenantId, Long workflowDefinitionId, PageRequest pageRequest) {
    return new WorkflowEdgeQuery(
        tenantId, workflowDefinitionId, null, null, null, null, null, pageRequest);
  }
}
