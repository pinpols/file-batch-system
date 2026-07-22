package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import java.util.List;
import java.util.Map;

/**
 * Application-layer payloads for worker task control use cases.
 *
 * <p>HTTP request/response DTOs stay in the controller package and are mapped at the web edge; the
 * task control service consumes these payloads so task orchestration logic does not depend on
 * controller types.
 */
public final class TaskControlPayloads {

  private TaskControlPayloads() {}

  public record TaskClaimCommand(String tenantId, String workerId, String partitionInvocationId) {}

  public record TaskClaimBatchCommand(List<TaskClaimItemCommand> items) {}

  public record TaskClaimItemCommand(
      String tenantId, Long taskId, String workerId, String partitionInvocationId) {}

  public record TaskClaimBatchResult(List<TaskClaimItemResult> results) {}

  public record TaskClaimItemResult(Long taskId, boolean claimed, EffectiveTaskConfig config) {}

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
