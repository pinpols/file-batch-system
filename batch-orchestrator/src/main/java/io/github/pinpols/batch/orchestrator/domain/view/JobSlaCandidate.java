package io.github.pinpols.batch.orchestrator.domain.view;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/** SLA 候选及其所属 Workflow 上下文的查询投影。 */
@Data
public class JobSlaCandidate {
  private Long id;
  private String tenantId;
  private LocalDate bizDate;
  private String jobCode;
  private String instanceNo;
  private String instanceStatus;
  private String traceId;
  private Instant deadlineAt;
  private Integer expectedDurationSeconds;
  private Instant startedAt;
  private Instant executionStartedAt;
  private Instant finishedAt;
  private Instant slaAlertedAt;
  private Long parentWorkflowRunId;
  private String parentNodeCode;
}
