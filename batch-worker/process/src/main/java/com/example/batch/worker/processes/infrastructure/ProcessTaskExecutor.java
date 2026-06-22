package com.example.batch.worker.processes.infrastructure;

import com.example.batch.common.enums.JobType;
import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Process pipeline 的 {@link BatchTaskExecutor} SPI 包装 — delegate 到现有 {@link
 * ProcessStepExecutionAdapter}。
 *
 * <p>P0 Phase 3.3 改造。同 ImportTaskExecutor 模板,见后者 Javadoc 和 {@code docs/design/task-spi-design.md}
 * §Phase 3。
 */
@Component
@RequiredArgsConstructor
public class ProcessTaskExecutor implements BatchTaskExecutor {

  private final ProcessStepExecutionAdapter delegate;

  @Override
  public String taskType() {
    return JobType.PROCESS.code();
  }

  @Override
  public TaskCapability capability() {
    return new TaskCapability(
        Set.of(ResourceKind.CPU, ResourceKind.DB), false, true, Duration.ofMinutes(20));
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
