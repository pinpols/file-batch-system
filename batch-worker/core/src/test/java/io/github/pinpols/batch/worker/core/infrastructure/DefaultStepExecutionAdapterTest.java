package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutorRegistry;
import io.github.pinpols.batch.common.spi.task.ResourceKind;
import io.github.pinpols.batch.common.spi.task.TaskCapability;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("步骤执行适配器: 执行器路由, 失败码映射与异常兜底")
class DefaultStepExecutionAdapterTest {

  @Test
  @DisplayName("命中已注册执行器时按任务类型路由, 上下文透传租户与作业身份并返回成功")
  void shouldRouteToRegisteredExecutor() {
    BatchTaskExecutor shell = stub("shell", ctx -> {
      assertThat(ctx.tenantId()).isEqualTo("t1");
      assertThat(ctx.jobCode()).isEqualTo("job-1");
      assertThat(ctx.workerId()).isEqualTo("w-1");
      assertThat(ctx.runtimeAttributes()).containsEntry(PipelineRuntimeKeys.TRACE_ID, "trace-1");
      return TaskResult.ok();
    });
    DefaultStepExecutionAdapter adapter =
        new DefaultStepExecutionAdapter(new BatchTaskExecutorRegistry(List.of(shell)));

    StepExecutionResponse resp = adapter.execute(new StepExecutionRequest(
        "t1", "job-1", "shell", "w-1", Map.of(PipelineRuntimeKeys.TRACE_ID, "trace-1")));

    assertThat(resp.success()).isTrue();
    assertThat(resp.code()).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("任务类型未注册时返回无可用执行器的失败码并回显类型")
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
  @DisplayName("执行器返回失败结果时映射为任务失败并保留原始错误信息")
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
  @DisplayName("执行器抛出未捕获异常时兜底为执行器故障并保留异常信息")
  void shouldCatchUncaughtExecutorException() {
    BatchTaskExecutor throwing = stub("http", ctx -> {
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

  @Test
  @DisplayName("步骤编码为原子类型时按载荷中的子类型路由, 并向执行器透传参数")
  void shouldRouteByPayloadTaskTypeAndPassParams() {
    // job_type=ATOMIC ⇒ stepCode="ATOMIC";真正子类型 + 参数都在 payload 里。
    BatchTaskExecutor sql = stub("sql", ctx -> {
      assertThat(ctx.parameters()).containsEntry("sql", "SELECT 1");
      assertThat(ctx.parameters()).containsEntry("taskType", "sql");
      return TaskResult.ok();
    });
    DefaultStepExecutionAdapter adapter =
        new DefaultStepExecutionAdapter(new BatchTaskExecutorRegistry(List.of(sql)));

    StepExecutionResponse resp = adapter.execute(new StepExecutionRequest(
        "t1",
        "job-1",
        "ATOMIC",
        "w-1",
        Map.of("payload", "{\"taskType\":\"sql\",\"sql\":\"SELECT 1\"}")));

    assertThat(resp.success()).isTrue();
    assertThat(resp.code()).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("重放能力按实际原子任务子类型和冻结载荷参数解析")
  void shouldResolveReplayCapabilityFromSelectedExecutorAndPayload() {
    AtomicReference<Map<String, Object>> resolvedConfig = new AtomicReference<>();
    ReplayCapabilityDeclaration capability = new ReplayCapabilityDeclaration(
        "sql",
        "1.2.3",
        List.of("DATABASE_WRITE"),
        IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC,
        "business-key",
        "transactional-upsert",
        ReplayCapabilityDeclaration.CURRENT_CONTRACT_VERSION);
    BatchTaskExecutor sql = new BatchTaskExecutor() {
      @Override
      public String taskType() {
        return "sql";
      }

      @Override
      public TaskCapability capability() {
        return TaskCapability.of(ResourceKind.CPU);
      }

      @Override
      public ReplayCapabilityDeclaration replayCapability(Map<String, Object> frozenConfig) {
        resolvedConfig.set(frozenConfig);
        return capability;
      }

      @Override
      public TaskResult execute(TaskContext context) {
        return TaskResult.ok();
      }
    };
    DefaultStepExecutionAdapter adapter =
        new DefaultStepExecutionAdapter(new BatchTaskExecutorRegistry(List.of(sql)));
    StepExecutionRequest request = new StepExecutionRequest(
        "t1",
        "job-1",
        "ATOMIC",
        "w-1",
        Map.of("payload", "{\"taskType\":\"sql\",\"statement\":\"UPDATE t SET c=1\"}"));

    ReplayCapabilityDeclaration actual = adapter.resolveReplayCapability(request);

    assertThat(actual).isEqualTo(capability);
    assertThat(resolvedConfig.get())
        .containsEntry("taskType", "sql")
        .containsEntry("statement", "UPDATE t SET c=1");
  }

  @Test
  @DisplayName("载荷缺少子类型时回退按步骤编码路由, 仍返回成功")
  void shouldFallBackToStepCodeWhenPayloadHasNoTaskType() {
    BatchTaskExecutor shell = stub("shell", ctx -> TaskResult.ok());
    DefaultStepExecutionAdapter adapter =
        new DefaultStepExecutionAdapter(new BatchTaskExecutorRegistry(List.of(shell)));

    StepExecutionResponse resp = adapter.execute(new StepExecutionRequest(
        "t1", "job-1", "shell", "w-1", Map.of("payload", "{\"command\":\"/bin/echo\"}")));

    assertThat(resp.success()).isTrue();
  }

  @Test
  @DisplayName("非幂等执行前先持久化当前租约标记,标记未确认时拒绝执行器")
  void shouldRequireDurableFenceBeforeNonReplayableExecutorRuns() {
    AtomicInteger executionCount = new AtomicInteger();
    BatchTaskExecutor executor = nonReplayable("sql", ctx -> {
      executionCount.incrementAndGet();
      return TaskResult.ok();
    });
    TaskExecutionClient taskExecutionClient = mock(TaskExecutionClient.class);
    when(taskExecutionClient.markNonIdempotentExecutionStarted("t1", 41L, "w-1", "inv-41"))
        .thenReturn(false);
    DefaultStepExecutionAdapter adapter = new DefaultStepExecutionAdapter(
        new BatchTaskExecutorRegistry(List.of(executor)), taskExecutionClient);

    StepExecutionResponse response = adapter.execute(new StepExecutionRequest(
        "t1", "job-1", "sql", "w-1", Map.of("taskId", 41L, "partitionInvocationId", "inv-41")));

    assertThat(response.success()).isFalse();
    assertThat(response.code()).isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    assertThat(executionCount).hasValue(0);
    verify(taskExecutionClient).markNonIdempotentExecutionStarted("t1", 41L, "w-1", "inv-41");
  }

  @Test
  @DisplayName("非幂等执行器只在持久化租约标记确认后运行")
  void shouldRunNonReplayableExecutorAfterDurableFence() {
    AtomicBoolean fenceConfirmed = new AtomicBoolean();
    BatchTaskExecutor executor = nonReplayable("shell", ctx -> {
      assertThat(fenceConfirmed).isTrue();
      return TaskResult.ok();
    });
    TaskExecutionClient taskExecutionClient = mock(TaskExecutionClient.class);
    when(taskExecutionClient.markNonIdempotentExecutionStarted("t1", 42L, "w-2", "inv-42"))
        .thenAnswer(invocation -> {
          fenceConfirmed.set(true);
          return true;
        });
    DefaultStepExecutionAdapter adapter = new DefaultStepExecutionAdapter(
        new BatchTaskExecutorRegistry(List.of(executor)), taskExecutionClient);

    StepExecutionResponse response = adapter.execute(new StepExecutionRequest(
        "t1", "job-1", "shell", "w-2", Map.of("taskId", 42L, "partitionInvocationId", "inv-42")));

    assertThat(response.success()).isTrue();
    verify(taskExecutionClient).markNonIdempotentExecutionStarted("t1", 42L, "w-2", "inv-42");
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────

  private static BatchTaskExecutor stub(String type, Function<TaskContext, TaskResult> fn) {
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

  private static BatchTaskExecutor nonReplayable(
      String type, Function<TaskContext, TaskResult> fn) {
    return new BatchTaskExecutor() {
      @Override
      public String taskType() {
        return type;
      }

      @Override
      public TaskCapability capability() {
        return new TaskCapability(Set.of(ResourceKind.CPU), false, true, Duration.ofMinutes(5));
      }

      @Override
      public TaskResult execute(TaskContext ctx) {
        return fn.apply(ctx);
      }
    };
  }
}
