package io.github.pinpols.batch.worker.processes.infrastructure;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.ResourceKind;
import io.github.pinpols.batch.common.spi.task.TaskCapability;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Process pipeline 的 {@link BatchTaskExecutor} SPI 包装 — delegate 到现有 {@link
 * ProcessStepExecutionAdapter}。
 *
 * <p>P0 Phase 3.3 改造。同 ImportTaskExecutor 模板,见后者 Javadoc 和 {@code docs/design/task-spi-design.md}
 * §Phase 3。
 */
@Component
public class ProcessTaskExecutor implements BatchTaskExecutor {

  private final ProcessStepExecutionAdapter delegate;
  private final WorkerExecutionTimeoutProperties timeoutProperties;

  /** 保留旧构造函数，兼容无 Spring 的 SDK/单测调用方。 */
  public ProcessTaskExecutor(ProcessStepExecutionAdapter delegate) {
    this(delegate, compatibilityDefaults());
  }

  @Autowired
  public ProcessTaskExecutor(
      ProcessStepExecutionAdapter delegate, WorkerExecutionTimeoutProperties timeoutProperties) {
    this.delegate = delegate;
    this.timeoutProperties = timeoutProperties;
  }

  private static WorkerExecutionTimeoutProperties compatibilityDefaults() {
    WorkerExecutionTimeoutProperties properties = new WorkerExecutionTimeoutProperties();
    properties.setCapabilityTimeoutSeconds(1200L);
    return properties;
  }

  @Override
  public String taskType() {
    return JobType.PROCESS.code();
  }

  @Override
  public TaskCapability capability() {
    return new TaskCapability(
        Set.of(ResourceKind.CPU, ResourceKind.DB),
        false,
        true,
        Duration.ofSeconds(timeoutProperties.getCapabilityTimeoutSeconds()));
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
