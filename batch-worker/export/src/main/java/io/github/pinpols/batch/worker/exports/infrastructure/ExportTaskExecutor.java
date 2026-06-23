package io.github.pinpols.batch.worker.exports.infrastructure;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.ResourceKind;
import io.github.pinpols.batch.common.spi.task.TaskCapability;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Export pipeline 的 {@link BatchTaskExecutor} SPI 包装 — delegate 到现有 {@link
 * ExportStepExecutionAdapter}。
 *
 * <p>P0 Phase 3.2 改造。完全跟 {@code ImportTaskExecutor} 一个模板,只换 delegate + taskType。 完整设计 + 约束见 {@code
 * ImportTaskExecutor} 的 Javadoc 和 {@code docs/design/task-spi-design.md} §Phase 3。
 */
@Component
@RequiredArgsConstructor
public class ExportTaskExecutor implements BatchTaskExecutor {

  private final ExportStepExecutionAdapter delegate;

  @Override
  public String taskType() {
    return JobType.EXPORT.code();
  }

  @Override
  public TaskCapability capability() {
    return new TaskCapability(
        java.util.Set.of(ResourceKind.DB, ResourceKind.DISK, ResourceKind.NET),
        false,
        true,
        Duration.ofMinutes(30));
  }

  @Override
  public TaskResult execute(TaskContext ctx) {
    StepExecutionRequest req =
        new StepExecutionRequest(
            ctx.tenantId(), ctx.jobCode(), taskType(), ctx.workerId(), ctx.runtimeAttributes());
    StepExecutionResponse resp = delegate.execute(req);
    if (resp.success()) {
      return TaskResult.ok(resp.message() == null ? "ok" : resp.message());
    }
    return TaskResult.fail(resp.message() == null ? resp.code() : resp.message());
  }
}
