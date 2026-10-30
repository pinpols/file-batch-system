package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * Worker 任务控制用例的应用层载荷。
 *
 * <p>HTTP request/response DTOs stay in the controller package and are mapped at the web edge; the
 * 任务控制服务接收这些载荷，使任务编排逻辑不依赖
 * controller types.
 */
public final class TaskControlPayloads {

  private TaskControlPayloads() {}

  public record TaskClaimCommand(String tenantId, String workerId, String partitionInvocationId) {}

  public record TaskNonIdempotentStartCommand(
      String tenantId, String workerId, String partitionInvocationId) {}

  public record TaskClaimBatchCommand(List<TaskClaimItemCommand> items) {}

  public record TaskClaimItemCommand(
      String tenantId, Long taskId, String workerId, String partitionInvocationId) {}

  public record TaskClaimBatchResult(List<TaskClaimItemResult> results) {}

  public record TaskClaimItemResult(Long taskId, boolean claimed, EffectiveTaskConfig config) {}

  @Builder
  public record TaskExecutionReportCommand(
      Long taskId,
      String tenantId,
      String workerId,
      boolean success,
      String code,
      String message,
      String resultSummary,
      String errorCode,
      String errorMessage,
      String errorKey,
      String errorArgs,
      String highWaterMarkOut,
      Map<String, Object> outputs,
      String partitionInvocationId,
      String failureClass,
      List<Map<String, Object>> verifierFailures) {}

  public record TaskReportBatchCommand(List<TaskExecutionReportCommand> items) {}

  public record TaskReportBatchResult(List<TaskReportItemResult> results) {}

  public record TaskReportItemResult(Long taskId, boolean ok, String error) {}

  public record TaskHeartbeatCommand(
      String tenantId, String workerId, String partitionInvocationId, Object details) {}

  public record TaskHeartbeatResult(boolean cancelRequested) {}

  public record TaskCancelCommand(String tenantId, String reason) {}

  public record TaskLeaseRenewBatchCommand(List<TaskLeaseRenewItemCommand> items) {}

  public record TaskLeaseRenewItemCommand(
      String tenantId, Long taskId, String workerId, String partitionInvocationId) {}

  public record TaskLeaseRenewBatchResult(List<TaskLeaseRenewItemResult> results) {}

  public record TaskLeaseRenewItemResult(Long taskId, boolean renewed, boolean cancelRequested) {}
}
