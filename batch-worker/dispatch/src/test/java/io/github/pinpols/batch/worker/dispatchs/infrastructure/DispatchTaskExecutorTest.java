package io.github.pinpols.batch.worker.dispatchs.infrastructure;

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

@DisplayName("分发任务执行器:任务类型与资源能力声明,以及把任务上下文翻译成步骤执行请求的成败语义")
class DispatchTaskExecutorTest {

  private DispatchStepExecutionAdapter delegate;
  private DispatchTaskExecutor executor;

  @BeforeEach
  void setUp() {
    delegate = mock(DispatchStepExecutionAdapter.class);
    executor = new DispatchTaskExecutor(delegate);
  }

  @Test
  @DisplayName("任务类型查询返回分发类型,供注册表按类型路由")
  void shouldReportDispatchTaskType_whenTaskTypeQueried() {
    assertThat(executor.taskType()).isEqualTo("DISPATCH");
  }

  @Test
  @DisplayName("能力声明含网络与磁盘资源,不承诺幂等但声明可取消")
  void shouldDeclareNetworkAndDiskResources_whenCapabilityQueried() {
    assertThat(executor.capability().resourceKinds()).contains(ResourceKind.NET, ResourceKind.DISK);
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
  }

  @Test
  @DisplayName("执行时把任务上下文翻译成步骤执行请求,步骤码与作业号随之传递")
  void shouldTranslateContextToStepRequest_whenExecute() {
    when(delegate.execute(any())).thenReturn(StepExecutionResponse.successResponse());

    TaskContext ctx =
        new TaskContext("tenant-1", "job-dispatch-1", "ti-9", "worker-7", Map.of(), Map.of());

    executor.execute(ctx);

    ArgumentCaptor<StepExecutionRequest> captor =
        ArgumentCaptor.forClass(StepExecutionRequest.class);
    verify(delegate).execute(captor.capture());
    assertThat(captor.getValue().stepCode()).isEqualTo("DISPATCH");
    assertThat(captor.getValue().jobCode()).isEqualTo("job-dispatch-1");
  }

  @Test
  @DisplayName("委派的步骤执行返回失败时,失败判定与失败原因原样透传")
  void shouldPropagateFailureMessage_whenDelegateFails() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "FAIL", "dispatch send error"));
    TaskResult r = executor.execute(simpleCtx());
    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("dispatch send error");
  }

  @Test
  @DisplayName("失败结果未带原因时,以失败码作为对外原因")
  void shouldFallBackToFailureCode_whenFailureMessageAbsent() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "DISPATCH_FAIL", null));
    assertThat(executor.execute(simpleCtx()).message()).isEqualTo("DISPATCH_FAIL");
  }

  private static TaskContext simpleCtx() {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", Map.of(), Map.of());
  }
}
