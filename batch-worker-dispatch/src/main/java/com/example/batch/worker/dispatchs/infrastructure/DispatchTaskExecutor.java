package com.example.batch.worker.dispatchs.infrastructure;

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
 * Dispatch pipeline 的 {@link BatchTaskExecutor} SPI 包装 — delegate 到现有 {@link
 * DispatchStepExecutionAdapter}。
 *
 * <p>P0 Phase 3.4 改造。同 ImportTaskExecutor 模板,见后者 Javadoc 和 {@code docs/design/task-spi-design.md}
 * §Phase 3。
 */
@Component
@RequiredArgsConstructor
public class DispatchTaskExecutor implements BatchTaskExecutor {

  private final DispatchStepExecutionAdapter delegate;

  @Override
  public String taskType() {
    return JobType.DISPATCH.code();
  }

  @Override
  public TaskCapability capability() {
    return new TaskCapability(
        java.util.Set.of(ResourceKind.NET, ResourceKind.DISK), false, true, Duration.ofMinutes(15));
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
