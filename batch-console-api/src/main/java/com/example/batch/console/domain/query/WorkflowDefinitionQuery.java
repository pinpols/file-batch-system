package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record WorkflowDefinitionQuery(
    String tenantId,
    String workflowCode,
    String workflowName,
    String workflowType,
    Integer version,
    Boolean enabled,
    PageRequest pageRequest) {

  /** 按租户全量查询，不带过滤条件。 */
  public static WorkflowDefinitionQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return new WorkflowDefinitionQuery(tenantId, null, null, null, null, null, pageRequest);
  }
}
