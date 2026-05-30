package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.BatchTaskExecutorRegistry;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultStepExecutionAdapterTest {

  @Test
  void shouldRouteToRegisteredExecutor() {
    BatchTaskExecutor shell =
        stub(
            "shell",
            ctx -> {
              assertThat(ctx.tenantId()).isEqualTo("t1");
              assertThat(ctx.jobCode()).isEqualTo("job-1");
              assertThat(ctx.workerId()).isEqualTo("w-1");
              assertThat(ctx.runtimeAttributes()).containsEntry("traceId", "trace-1");
              return TaskResult.ok();
            });
    DefaultStepExecutionAdapter adapter =
        new DefaultStepExecutionAdapter(new BatchTaskExecutorRegistry(List.of(shell)));

    StepExecutionResponse resp =
        adapter.execute(
            new StepExecutionRequest("t1", "job-1", "shell", "w-1", Map.of("traceId", "trace-1")));

    assertThat(resp.success()).isTrue();
    assertThat(resp.code()).isEqualTo("SUCCESS");
  }

  @Test
  void shouldReturnNoExecutorFailureForUnknownType() {
    DefaultStepExecutionAdapter adapter =
        new DefaultStepExecutionAdapter(new BatchTaskExecutorRegistry(List.of()));

    StepExecutionResponse resp =
        adapter.execute(new StepExecutionRequest("t1", "job-1", "nope", "w-1", Map.of()));

    assertThat(resp.success()).isFalse();
    assertThat(resp.code()).isEqualTo("NO_EXECUTOR");
    assertThat(resp.message()).contains("nope");
  }

  @Test
  void shouldReturnTaskFailedWhenExecutorReturnsFailure() {
    BatchTaskExecutor failing = stub("sql", ctx -> TaskResult.fail("syntax error"));
    DefaultStepExecutionAdapter adapter =
        new DefaultStepExecutionAdapter(new BatchTaskExecutorRegistry(List.of(failing)));

    StepExecutionResponse resp =
        adapter.execute(new StepExecutionRequest("t1", "job-1", "sql", "w-1", Map.of()));

    assertThat(resp.success()).isFalse();
    assertThat(resp.code()).isEqualTo("TASK_FAILED");
    assertThat(resp.message()).isEqualTo("syntax error");
  }

  @Test
  void shouldCatchUncaughtExecutorException() {
    BatchTaskExecutor throwing =
        stub(
            "http",
            ctx -> {
              throw new RuntimeException("network kaboom");
            });
    DefaultStepExecutionAdapter adapter =
        new DefaultStepExecutionAdapter(new BatchTaskExecutorRegistry(List.of(throwing)));

    StepExecutionResponse resp =
        adapter.execute(new StepExecutionRequest("t1", "job-1", "http", "w-1", Map.of()));

    assertThat(resp.success()).isFalse();
    assertThat(resp.code()).isEqualTo("EXECUTOR_FAILURE");
    assertThat(resp.message()).isEqualTo("network kaboom");
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────

  private static BatchTaskExecutor stub(
      String type, java.util.function.Function<TaskContext, TaskResult> fn) {
    return new BatchTaskExecutor() {
      @Override
      public String taskType() {
        return type;
      }

      @Override
      public TaskCapability capability() {
        return TaskCapability.of(ResourceKind.CPU);
      }

      @Override
      public TaskResult execute(TaskContext ctx) {
        return fn.apply(ctx);
      }
    };
  }
}
