package io.github.pinpols.batch.console.domain.job.application.contract.response;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
public record JobExecutionProgressResponse(
    String tenantId,
    Long jobInstanceId,
    String jobCode,
    String instanceStatus,
    Instant generatedAt,
    Instant startedAt,
    Instant finishedAt,
    Integer expectedPartitions,
    Long workflowRunId,
    String nodeCode,
    Long pipelineInstanceId,
    String pipelineStatus,
    String currentStage,
    String systemPipelineCode,
    Integer systemPipelineVersion,
    boolean stageGraphAvailable,
    List<StatusCount> counts,
    List<Stage> stages) {
  public record StatusCount(String scope, String status, Long count) {}

  public record Stage(
      String stageCode,
      String stageStatus,
      Long stepRunId,
      Integer runSeq,
      Long rowsProcessed,
      Instant lastHeartbeatAt,
      String errorCode) {}
}
