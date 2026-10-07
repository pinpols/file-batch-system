package io.github.pinpols.batch.worker.processes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.spi.task.ResourceKind;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("处理任务执行器:任务类型与资源能力声明,以及把任务上下文翻译成步骤执行请求的成败语义")
class ProcessTaskExecutorTest {

  private ProcessStepExecutionAdapter delegate;
  private ProcessTaskExecutor executor;

  @BeforeEach
  void setUp() {
    delegate = mock(ProcessStepExecutionAdapter.class);
    executor = new ProcessTaskExecutor(delegate);
  }

  @Test
  @DisplayName("任务类型查询返回处理类型,供注册表按类型路由")
  void shouldReportProcessTaskType_whenTaskTypeQueried() {
    assertThat(executor.taskType()).isEqualTo("PROCESS");
  }

  @Test
  @DisplayName("能力声明处理器与数据库资源,不承诺幂等但声明可取消")
  void shouldDeclareCpuAndDbResources_whenCapabilityQueried() {
    assertThat(executor.capability().resourceKinds()).contains(ResourceKind.CPU, ResourceKind.DB);
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
  }

  @Test
  @DisplayName("执行时把任务上下文翻译成步骤执行请求,步骤码与作业号随之传递")
  void shouldTranslateContextToStepRequest_whenExecute() {
    when(delegate.execute(any())).thenReturn(StepExecutionResponse.successResponse());

    TaskContext ctx =
        new TaskContext("tenant-1", "job-process-1", "ti-9", "worker-7", Map.of(), Map.of());

    executor.execute(ctx);

    ArgumentCaptor<StepExecutionRequest> captor =
        ArgumentCaptor.forClass(StepExecutionRequest.class);
    verify(delegate).execute(captor.capture());
    assertThat(captor.getValue().stepCode()).isEqualTo("PROCESS");
    assertThat(captor.getValue().jobCode()).isEqualTo("job-process-1");
  }

  @Test
  @DisplayName("委派的步骤执行返回失败时,失败判定与失败原因原样透传")
  void shouldPropagateFailureMessage_whenDelegateFails() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "FAIL", "process stage error"));
    TaskResult r = executor.execute(simpleCtx());
    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("process stage error");
  }

  @Test
  @DisplayName("失败结果未带原因时,以失败码作为对外原因")
  void shouldFallBackToFailureCode_whenFailureMessageAbsent() {
    when(delegate.execute(any())).thenReturn(new StepExecutionResponse(false, "PROC_FAIL", null));
    assertThat(executor.execute(simpleCtx()).message()).isEqualTo("PROC_FAIL");
  }

  private static TaskContext simpleCtx() {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", Map.of(), Map.of());
  }
}
