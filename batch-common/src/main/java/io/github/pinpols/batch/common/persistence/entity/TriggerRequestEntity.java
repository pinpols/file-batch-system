package io.github.pinpols.batch.common.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class TriggerRequestEntity {

  private Long id;
  private String tenantId;
  private String requestId;
  private String triggerType;
  private String jobCode;
  private Long workflowDefinitionId;
  private LocalDate bizDate;
  private String dedupKey;
  private String requestStatus;
  private Long relatedJobInstanceId;
  private Long parentWorkflowRunId;
  private String parentNodeCode;
  private String traceId;
  private int forwardRetryCount;
  private Instant createdAt;
  private Instant updatedAt;

  /** ADR-026 dry-run 演练标记；trigger 入口染色，落到 LaunchRequest.dryRun 透传到主链路。 */
  private Boolean dryRun;

  /** 已接收的原始启动命令；在审批或异步恢复前冻结保存。 */
  private String launchPayload;

  private String externalEventCode;
  private String externalEventSource;
  private String externalEventId;
}
