package io.github.pinpols.batch.orchestrator.domain.view;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** 根运行或尚未创建根的预期机会；关闭机会不伪装成 Workflow 的实际完成时间。 */
@Builder
public record WorkflowMonitoringAlertCandidate(
    String tenantId,
    Long workflowDefinitionId,
    String workflowCode,
    Long workflowRunId,
    Long occurrenceId,
    LocalDate bizDate,
    String traceId,
    String resourceKey,
    Instant expectedAt,
    Instant startedAt,
    Instant finishedAt,
    Instant opportunityClosedAt,
    Instant monitoringDeadlineAt,
    String severity,
    Violation violation) {

  public enum Violation {
    NOT_STARTED("WORKFLOW_NOT_STARTED_BY_DEADLINE", "Workflow 开始过晚"),
    NOT_COMPLETED_BY_DEADLINE("WORKFLOW_NOT_COMPLETED_BY_DEADLINE", "Workflow 完成过晚"),
    RUNNING_TOO_LONG("WORKFLOW_RUNNING_TOO_LONG", "Workflow 运行耗时超限");

    private final String alertType;
    private final String title;

    Violation(String alertType, String title) {
      this.alertType = alertType;
      this.title = title;
    }

    public String alertType() {
      return alertType;
    }

    public String title(String workflowCode) {
      return title + "：" + workflowCode;
    }
  }
}
