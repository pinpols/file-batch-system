package com.example.batch.console.domain.workflow.query;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import lombok.Builder;

@Builder
public record WorkflowDefinitionQuery(
    String tenantId,
    String workflowCode,
    String workflowName,
    String workflowType,
    Integer version,
    Boolean enabled,
    PageRequest pageRequest) {

  /**
   * P1-10 (pre-launch audit 2026-05-18)：tenantId 非空断言。 Mapper SQL 已将 tenant_id 改为强制条件,这里在 record
   * canonical 构造期就拦截 null/blank, 把跨租漏洞失败前移到构造点而非 SQL 触发点。
   */
  public WorkflowDefinitionQuery {
    if (tenantId == null || tenantId.isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.workflow.tenant_id_required");
    }
  }

  /** 按租户全量查询，不带过滤条件。 */
  public static WorkflowDefinitionQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return builder().tenantId(tenantId).pageRequest(pageRequest).build();
  }
}
