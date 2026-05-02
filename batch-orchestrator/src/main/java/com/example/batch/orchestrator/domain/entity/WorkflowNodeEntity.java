package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WorkflowNodeEntity {

  private Long id;
  private String tenantId;
  private Long workflowDefinitionId;
  private String nodeCode;
  private String nodeName;
  private String nodeType;
  private String relatedJobCode;

  /** 节点级编排中工作流与 Pipeline 的关联码，勿与 Pipeline 定义及运行时上下文使用的标准 Job Code 混淆。 */
  private String relatedPipelineCode;

  private String workerGroup;
  private String windowCode;
  private Integer nodeOrder;
  private String retryPolicy;
  private Integer retryMaxCount;
  private Integer timeoutSeconds;
  private String nodeParams;
  private Boolean enabled;
  private Instant createdAt;
  private Instant updatedAt;
}
