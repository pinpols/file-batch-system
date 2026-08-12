package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskExecutionReportCommand;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/** 单条 Worker 结果上报的死锁重试边界；批量上报通过跨 Bean 调用复用同一策略。 */
@Component
@RequiredArgsConstructor
public class TaskReportRetryExecutor {

  private final TaskExecutionService taskExecutionService;

  @Retryable(
      retryFor = {CannotAcquireLockException.class, TransientDataAccessException.class},
      maxAttempts = 5,
      backoff = @Backoff(delay = 50, maxDelay = 1000, multiplier = 2.0, random = true))
  public void report(Long taskId, TaskExecutionReportCommand request) {
    String errorCode = resolveFailureField(request.errorCode(), request.code(), request.success());
    String errorMessage =
        resolveFailureField(request.errorMessage(), request.message(), request.success());
    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
        .tenantId(request.tenantId())
        .taskId(taskId)
        .workerId(request.workerId())
        .success(request.success())
        .resultSummary(request.resultSummary())
        .errorCode(errorCode)
        .errorMessage(errorMessage)
        .errorKey(request.errorKey())
        .errorArgs(request.errorArgs())
        .highWaterMarkOut(request.highWaterMarkOut())
        .outputs(request.outputs())
        .partitionInvocationId(request.partitionInvocationId())
        .failureClass(request.success() ? null : request.failureClass())
        .verifierFailures(request.success() ? request.verifierFailures() : null)
        .build();
    taskExecutionService.applyTaskOutcome(command);
  }

  private static String resolveFailureField(String modern, String legacy, boolean success) {
    if (success) {
      return null;
    }
    if (modern != null && !modern.isBlank()) {
      return modern;
    }
    if (legacy != null && !legacy.isBlank()) {
      return legacy;
    }
    return "UNKNOWN";
  }
}
