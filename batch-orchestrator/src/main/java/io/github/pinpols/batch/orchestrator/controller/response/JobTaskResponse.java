package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import java.time.Instant;

/** 已认领任务的 HTTP 表示，保持 Worker 使用的 JSON 字段名。 */
public record JobTaskResponse(
    Long id,
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    String taskType,
    Integer taskSeq,
    String taskStatus,
    String assignedWorkerCode,
    Long version,
    String taskPayload,
    String effectiveParameters,
    String heartbeatDetails,
    Instant heartbeatAt,
    Boolean cancelRequested,
    String resultSummary,
    String errorCode,
    Integer priority,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt,
    Instant updatedAt,
    String failureClass,
    Boolean dryRun,
    Integer taskTimeoutSeconds,
    String errorMessage,
    String errorKey,
    String errorArgs,
    String status) {

  public static JobTaskResponse from(JobTaskEntity entity) {
    return new JobTaskResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getJobInstanceId(),
        entity.getJobPartitionId(),
        entity.getTaskType(),
        entity.getTaskSeq(),
        entity.getTaskStatus(),
        entity.getAssignedWorkerCode(),
        entity.getVersion(),
        entity.getTaskPayload(),
        entity.getEffectiveParameters(),
        entity.getHeartbeatDetails(),
        entity.getHeartbeatAt(),
        entity.getCancelRequested(),
        entity.getResultSummary(),
        entity.getErrorCode(),
        entity.getPriority(),
        entity.getStartedAt(),
        entity.getFinishedAt(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getFailureClass(),
        entity.getDryRun(),
        entity.getTaskTimeoutSeconds(),
        entity.getErrorMessage(),
        entity.getErrorKey(),
        entity.getErrorArgs(),
        entity.getStatus());
  }
}
