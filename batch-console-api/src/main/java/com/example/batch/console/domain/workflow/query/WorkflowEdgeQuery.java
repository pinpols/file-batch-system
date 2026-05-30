package com.example.batch.console.domain.workflow.query;

import com.example.batch.common.model.PageRequest;
import lombok.Builder;

@Builder
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
    return builder().workflowDefinitionId(workflowDefinitionId).pageRequest(pageRequest).build();
  }

  /** 按租户 + workflowDefinitionId 查询全部边。 */
  public static WorkflowEdgeQuery ofDefinition(
      String tenantId, Long workflowDefinitionId, PageRequest pageRequest) {
    return builder()
        .tenantId(tenantId)
        .workflowDefinitionId(workflowDefinitionId)
        .pageRequest(pageRequest)
        .build();
  }
}
