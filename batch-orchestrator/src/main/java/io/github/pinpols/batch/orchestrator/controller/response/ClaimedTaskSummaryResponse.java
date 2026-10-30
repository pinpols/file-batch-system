package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import java.time.Instant;

/** Worker 运维查询的任务安全摘要，不包含任务载荷、结果正文或错误消息。 */
public record ClaimedTaskSummaryResponse(
    Long id,
    Long jobInstanceId,
    Long jobPartitionId,
    String taskType,
    Integer taskSeq,
    String taskStatus,
    String assignedWorkerCode,
    String errorCode,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt,
    Instant updatedAt) {

  public static ClaimedTaskSummaryResponse from(JobTaskEntity entity) {
    return new ClaimedTaskSummaryResponse(
        entity.getId(),
        entity.getJobInstanceId(),
        entity.getJobPartitionId(),
        entity.getTaskType(),
        entity.getTaskSeq(),
        entity.getTaskStatus(),
        entity.getAssignedWorkerCode(),
        entity.getErrorCode(),
        entity.getStartedAt(),
        entity.getFinishedAt(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
