package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import java.time.Instant;
import lombok.Builder;

/** 已认领任务的 HTTP 表示，保持 Worker 使用的 JSON 字段名。 */
@Builder
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
    return JobTaskResponse.builder()
        .id(entity.getId())
        .tenantId(entity.getTenantId())
        .jobInstanceId(entity.getJobInstanceId())
        .jobPartitionId(entity.getJobPartitionId())
        .taskType(entity.getTaskType())
        .taskSeq(entity.getTaskSeq())
        .taskStatus(entity.getTaskStatus())
        .assignedWorkerCode(entity.getAssignedWorkerCode())
        .version(entity.getVersion())
        .taskPayload(entity.getTaskPayload())
        .effectiveParameters(entity.getEffectiveParameters())
        .heartbeatDetails(entity.getHeartbeatDetails())
        .heartbeatAt(entity.getHeartbeatAt())
        .cancelRequested(entity.getCancelRequested())
        .resultSummary(entity.getResultSummary())
        .errorCode(entity.getErrorCode())
        .priority(entity.getPriority())
        .startedAt(entity.getStartedAt())
        .finishedAt(entity.getFinishedAt())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .failureClass(entity.getFailureClass())
        .dryRun(entity.getDryRun())
        .taskTimeoutSeconds(entity.getTaskTimeoutSeconds())
        .errorMessage(entity.getErrorMessage())
        .errorKey(entity.getErrorKey())
        .errorArgs(entity.getErrorArgs())
        .status(entity.getStatus())
        .build();
  }
}
