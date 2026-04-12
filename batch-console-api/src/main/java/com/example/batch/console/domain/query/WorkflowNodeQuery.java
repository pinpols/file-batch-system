package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record WorkflowNodeQuery(
    String tenantId,
    Long workflowDefinitionId,
    String workflowCode,
    String nodeCode,
    String nodeType,
    Boolean enabled,
    PageRequest pageRequest) {

  /** 按 workflowDefinitionId 查询全部节点，不带其他过滤条件。 */
  public static WorkflowNodeQuery ofDefinition(Long workflowDefinitionId, PageRequest pageRequest) {
    return new WorkflowNodeQuery(null, workflowDefinitionId, null, null, null, null, pageRequest);
  }

  /** 按租户 + workflowDefinitionId 查询全部节点。 */
  public static WorkflowNodeQuery ofDefinition(
      String tenantId, Long workflowDefinitionId, PageRequest pageRequest) {
    return new WorkflowNodeQuery(
        tenantId, workflowDefinitionId, null, null, null, null, pageRequest);
  }
}
