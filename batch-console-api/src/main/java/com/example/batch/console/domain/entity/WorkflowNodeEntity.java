package com.example.batch.console.domain.entity;

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
  private String relatedPipelineCode;
  private String workerGroup;
  private String windowCode;
  private Integer nodeOrder;
  private String retryPolicy;
  private Integer retryMaxCount;
  private Integer timeoutSeconds;
  private String nodeParams;

  /** ADR-018 跨日依赖 spec JSONB（数组）。 */
  private String crossDayDependencies;

  /** ADR-018 跨日依赖等待 timeout（秒）。 */
  private Integer crossDayDependencyTimeoutSeconds;

  private Boolean enabled;
  private Instant createdAt;
  private Instant updatedAt;
}
