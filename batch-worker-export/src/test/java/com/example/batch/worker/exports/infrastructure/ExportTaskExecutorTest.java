package com.example.batch.worker.exports.infrastructure;

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

/** {@link ExportTaskExecutor} 单测 — 跟 {@code ImportTaskExecutorTest} 同 pattern。 */
class ExportTaskExecutorTest {

  private ExportStepExecutionAdapter delegate;
  private ExportTaskExecutor executor;

  @BeforeEach
  void setUp() {
    delegate = mock(ExportStepExecutionAdapter.class);
    executor = new ExportTaskExecutor(delegate);
  }

  @Test
  void taskTypeIsExport() {
    assertThat(executor.taskType()).isEqualTo("EXPORT");
  }

  @Test
  void capabilityDeclaresExpectedResources() {
    assertThat(executor.capability().resourceKinds())
        .contains(ResourceKind.DB, ResourceKind.DISK, ResourceKind.NET);
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
  }

  @Test
  void executeTranslatesContextToStepRequest() {
    when(delegate.execute(any())).thenReturn(StepExecutionResponse.successResponse());

    TaskContext ctx =
        new TaskContext(
            "tenant-1",
            "job-export-1",
            "ti-9",
            "worker-7",
            Map.of(),
            Map.of("pipelineInstanceId", 42L));

    TaskResult r = executor.execute(ctx);

    assertThat(r.success()).isTrue();
    ArgumentCaptor<StepExecutionRequest> captor =
        ArgumentCaptor.forClass(StepExecutionRequest.class);
    verify(delegate).execute(captor.capture());
    StepExecutionRequest sent = captor.getValue();
    assertThat(sent.stepCode()).isEqualTo("EXPORT");
    assertThat(sent.tenantId()).isEqualTo("tenant-1");
    assertThat(sent.jobCode()).isEqualTo("job-export-1");
  }

  @Test
  void failureResponsePropagatesMessage() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "PIPELINE_FAILED", "stage QUERY failed"));

    TaskResult r = executor.execute(simpleCtx());

    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("stage QUERY failed");
  }

  @Test
  void failureWithoutMessageFallsBackToCode() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "EXPORT_FAILED", null));
    TaskResult r = executor.execute(simpleCtx());
    assertThat(r.message()).isEqualTo("EXPORT_FAILED");
  }

  private static TaskContext simpleCtx() {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", Map.of(), Map.of());
  }
}
