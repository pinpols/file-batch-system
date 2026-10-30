package io.github.pinpols.batch.console.domain.workflow.application.contract.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

/** 运行冻结图和实际进度；任务数仅表示已生成任务，不能用作全部未来工作量。 */
@Builder
public record WorkflowExecutionProgressResponse(
    Long workflowRunId,
    String tenantId,
    Long workflowDefinitionId,
    Integer workflowVersion,
    String runStatus,
    LocalDate bizDate,
    Instant startedAt,
    Instant finishedAt,
    boolean snapshotAvailable,
    Instant generatedAt,
    List<NodeProgress> nodes,
    List<Edge> edges) {

  @Builder
  public record NodeProgress(
      String nodeCode,
      String nodeName,
      String nodeType,
      String relatedJobCode,
      String nodeStatus,
      Long nodeRunId,
      Integer runSeq,
      Long jobInstanceId,
      String jobInstanceStatus,
      Integer expectedPartitions,
      Integer completedPartitions,
      Long createdTasks,
      Long finishedTasks,
      List<String> activeSteps,
      String errorCode,
      String skipReasonCode,
      Instant startedAt,
      Instant finishedAt) {}

  public record Edge(String fromNodeCode, String toNodeCode, String edgeType) {}
}
