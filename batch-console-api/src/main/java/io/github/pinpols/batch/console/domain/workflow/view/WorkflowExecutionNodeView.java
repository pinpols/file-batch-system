package io.github.pinpols.batch.console.domain.workflow.view;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 同一次运行每个节点的最新尝试及其已生成工作量，均不读取任务载荷。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowExecutionNodeView {
  private Long id;
  private String nodeCode;
  private String nodeStatus;
  private Integer runSeq;
  private Long jobInstanceId;
  private String jobInstanceStatus;
  private String errorCode;
  private String skipReasonCode;
  private Instant startedAt;
  private Instant finishedAt;
  private Integer expectedPartitions;
  private Integer successPartitions;
  private Integer failedPartitions;
  private Integer cancelledPartitions;
  private Integer terminatedPartitions;
  private Long createdTasks;
  private Long finishedTasks;
  private String activeSteps;
}
