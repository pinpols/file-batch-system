package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/** Console 安全等待投影；不包含 workflow_launch_wait.launch_payload。 */
@Data
public class PersistentWaitReadRow {
  private Long id;
  private String waitType;
  private String tenantId;
  private Long workflowDefinitionId;
  private String workflowCode;
  private String calendarCode;
  private LocalDate bizDate;
  private String waitStatus;
  private String dependencyJobCodes;
  private String reasonCode;
  private Integer attemptCount;
  private Instant nextAttemptAt;
  private Instant deadline;
  private Instant scheduledFireTime;
  private Instant deferredSince;
  private Instant createdAt;
  private Instant updatedAt;
  private String requestId;
  private String traceId;
  private String lastErrorCode;
  private Long workflowRunId;
  private Boolean dryRun;
}
