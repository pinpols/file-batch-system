package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class FilePipelineStepQueryRequest extends PageQueryRequest {

  /** 租户 ID；由 ConsoleTenantGuard 在 service 层 resolve 后强制下推到 mapper。 */
  private String tenantId;

  private Long pipelineInstanceId;
  private String stepCode;
  private String stageCode;
  private String stepStatus;
}
