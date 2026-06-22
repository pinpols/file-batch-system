package com.example.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DispatchTaskExecutorTest {

  private DispatchStepExecutionAdapter delegate;
  private DispatchTaskExecutor executor;

  @BeforeEach
  void setUp() {
    delegate = mock(DispatchStepExecutionAdapter.class);
    executor = new DispatchTaskExecutor(delegate);
  }

  @Test
  void taskTypeIsDispatch() {
    assertThat(executor.taskType()).isEqualTo("DISPATCH");
  }

  @Test
  void capabilityDeclaresExpectedResources() {
    assertThat(executor.capability().resourceKinds()).contains(ResourceKind.NET, ResourceKind.DISK);
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
  }

  @Test
  void executeTranslatesContextToStepRequest() {
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
  void failurePropagatesMessage() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "FAIL", "dispatch send error"));
    TaskResult r = executor.execute(simpleCtx());
    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("dispatch send error");
  }

  @Test
  void failureWithoutMessageFallsBackToCode() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "DISPATCH_FAIL", null));
    assertThat(executor.execute(simpleCtx()).message()).isEqualTo("DISPATCH_FAIL");
  }

  private static TaskContext simpleCtx() {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", Map.of(), Map.of());
  }
}
