package com.example.batch.worker.exports.infrastructure;

import com.example.batch.common.enums.JobType;
import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
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
