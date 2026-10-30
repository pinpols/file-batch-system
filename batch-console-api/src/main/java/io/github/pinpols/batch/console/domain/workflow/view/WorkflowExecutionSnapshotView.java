package io.github.pinpols.batch.console.domain.workflow.view;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/** 仅供运行图投影；原始快照不得直接作为接口响应。 */
@Data
public class WorkflowExecutionSnapshotView {
  private Long id;
  private String tenantId;
  private Long workflowDefinitionId;
  private Integer workflowVersionSnapshot;
  private String runStatus;
  private LocalDate bizDate;
  private Instant startedAt;
  private Instant finishedAt;
  private String executionSnapshot;
}
