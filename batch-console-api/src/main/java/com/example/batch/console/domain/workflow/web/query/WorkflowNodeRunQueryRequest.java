package com.example.batch.console.domain.workflow.web.query;

import com.example.batch.console.web.query.PageQueryRequest;
import lombok.Data;

@Data
public class WorkflowNodeRunQueryRequest extends PageQueryRequest {

  /** 租户 ID；由 ConsoleTenantGuard 在 service 层 resolve 后传入 mapper，强制隔离。 */
  private String tenantId;

  private Long workflowRunId;
  private String nodeCode;
  private String nodeStatus;
  private String traceId;
}
