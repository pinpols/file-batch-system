package io.github.pinpols.batch.orchestrator.domain.entity;

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

  private String workerGroup;
  private String windowCode;
  private Integer nodeOrder;
  private String retryPolicy;
  private Integer retryMaxCount;
  private Integer timeoutSeconds;

  /** ORCH-P4-2 task 级 startToClose timeout（秒）；NULL/0 = 无 timeout，超时平台主动 cancel。 */
  private Integer taskTimeoutSeconds;

  private String nodeParams;

  /** ADR-018 跨批量日依赖声明（JSONB 数组）；NULL = 无跨日依赖。 */
  private String crossDayDependencies;

  /** ADR-018 跨日依赖等待上限秒数；0 = 永不超时。 */
  private Integer crossDayDependencyTimeoutSeconds;

  private Boolean enabled;
  private Instant createdAt;
  private Instant updatedAt;
}
