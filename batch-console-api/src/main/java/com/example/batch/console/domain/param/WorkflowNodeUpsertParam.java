package com.example.batch.console.domain.param;

import lombok.Data;

@Data
public class WorkflowNodeUpsertParam {

  private String tenantId;
  private Long workflowDefinitionId;
  private String nodeCode;
  private String nodeName;
  private String nodeType;
  private String relatedJobCode;
  private String relatedPipelineCode;
  private String workerGroup;
  private String windowCode;
  private Integer nodeOrder;
  private String retryPolicy;
  private Integer retryMaxCount;
  private Integer timeoutSeconds;
  private String nodeParams;
  private Boolean enabled;
}
