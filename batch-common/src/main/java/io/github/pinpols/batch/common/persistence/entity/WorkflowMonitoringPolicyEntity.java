package io.github.pinpols.batch.common.persistence.entity;

import java.time.Instant;
import java.time.LocalTime;
import lombok.Data;

/** Workflow 根级监控策略；Job 策略及运行状态保持独立。 */
@Data
public class WorkflowMonitoringPolicyEntity {
  private String tenantId;
  private Long workflowDefinitionId;
  private int softRuntimeSeconds;
  private String softRuntimeSeverity = "WARN";
  private int startGraceSeconds;
  private String startGraceSeverity = "WARN";
  private LocalTime completionDeadlineLocalTime;
  private int completionDeadlineDayOffset;
  private String completionDeadlineSeverity = "WARN";
  private Instant policyEffectiveAt;
  private Instant createdAt;
  private Instant updatedAt;
}
