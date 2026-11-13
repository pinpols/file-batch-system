package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.utils.EmptyChecks;
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

  /** 任务结果上报遇到锁冲突/瞬时数据库异常的最大尝试次数（含首次）。 */
  private static final int REPORT_RETRY_MAX_ATTEMPTS = 5;

  /** 重试首次退避毫秒数，此后按 2 倍随机递增。 */
  private static final int REPORT_RETRY_INITIAL_DELAY_MILLIS = 50;

  /** 重试退避上限毫秒数。 */
  private static final int REPORT_RETRY_MAX_DELAY_MILLIS = 1000;

  private final TaskOutcomeService taskOutcomeService;

  @Retryable(
      retryFor = {CannotAcquireLockException.class, TransientDataAccessException.class},
      maxAttempts = REPORT_RETRY_MAX_ATTEMPTS,
      backoff =
          @Backoff(
              delay = REPORT_RETRY_INITIAL_DELAY_MILLIS,
              maxDelay = REPORT_RETRY_MAX_DELAY_MILLIS,
              multiplier = 2.0,
              random = true))
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
    taskOutcomeService.applyTaskOutcome(command);
  }

  private static String resolveFailureField(String modern, String legacy, boolean success) {
    if (success) {
      return null;
    }
    if (EmptyChecks.isNotBlank(modern)) {
      return modern;
    }
    if (EmptyChecks.isNotBlank(legacy)) {
      return legacy;
    }
    return "UNKNOWN";
  }
}
