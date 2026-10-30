package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/** Orchestrator 接管的 readiness 持久等待。 */
@Data
public class ReadinessWaitEntity {

  private Long id;
  private String tenantId;
  private Long workflowDefinitionId;
  private String workflowCode;
  private String calendarCode;
  private Integer scheduleVersion;
  private String dependsOnJobCodes;
  private LocalDate bizDate;
  private Instant scheduledFireTime;
  private Instant deferredSince;
  private Instant deadline;
  private String waitStatus;
  private String requestId;
  private String traceId;
  private String lastErrorCode;
  private Instant releasedAt;
  private Instant timedOutAt;
}
