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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProcessTaskExecutorTest {

  private ProcessStepExecutionAdapter delegate;
  private ProcessTaskExecutor executor;

  @BeforeEach
  void setUp() {
    delegate = mock(ProcessStepExecutionAdapter.class);
    executor = new ProcessTaskExecutor(delegate);
  }

  @Test
  void taskTypeIsProcess() {
    assertThat(executor.taskType()).isEqualTo("PROCESS");
  }

  @Test
  void capabilityDeclaresExpectedResources() {
    assertThat(executor.capability().resourceKinds()).contains(ResourceKind.CPU, ResourceKind.DB);
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
  }

  @Test
  void executeTranslatesContextToStepRequest() {
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
  void failurePropagatesMessage() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "FAIL", "process stage error"));
    TaskResult r = executor.execute(simpleCtx());
    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("process stage error");
  }

  @Test
  void failureWithoutMessageFallsBackToCode() {
    when(delegate.execute(any())).thenReturn(new StepExecutionResponse(false, "PROC_FAIL", null));
    assertThat(executor.execute(simpleCtx()).message()).isEqualTo("PROC_FAIL");
  }

  private static TaskContext simpleCtx() {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", Map.of(), Map.of());
  }
}
