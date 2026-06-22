package com.example.batch.worker.imports.infrastructure;

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

/**
 * {@link ImportTaskExecutor} 单测 — 验证 SPI 包装路径跟直调 ImportStepExecutionAdapter **行为等价**。
 *
 * <p>不引 Spring context,直接构造 + mock delegate。Pipeline lifecycle 行为本身由
 * AbstractPipelineStepExecutionAdapterTest 覆盖,本测试只关心包装层翻译是否正确。
 */
class ImportTaskExecutorTest {

  private ImportStepExecutionAdapter delegate;
  private ImportTaskExecutor executor;

  @BeforeEach
  void setUp() {
    delegate = mock(ImportStepExecutionAdapter.class);
    executor = new ImportTaskExecutor(delegate);
  }

  @Test
  void taskTypeIsImport() {
    assertThat(executor.taskType()).isEqualTo("IMPORT");
  }

  @Test
  void capabilityDeclaresExpectedResources() {
    assertThat(executor.capability().resourceKinds())
        .contains(ResourceKind.DISK, ResourceKind.DB, ResourceKind.NET);
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
  }

  @Test
  void executeTranslatesContextToStepRequestPreservingFields() {
    when(delegate.execute(any())).thenReturn(StepExecutionResponse.successResponse());

    TaskContext ctx =
        new TaskContext(
            "tenant-1",
            "job-import-1",
            "ti-9",
            "worker-7",
            Map.of(), // SPI parameters 不映射(Phase 3 不做),走 runtimeAttributes
            Map.of("pipelineInstanceId", 42L, "traceId", "trace-abc"));

    TaskResult r = executor.execute(ctx);

    assertThat(r.success()).isTrue();

    ArgumentCaptor<StepExecutionRequest> captor =
        ArgumentCaptor.forClass(StepExecutionRequest.class);
    verify(delegate).execute(captor.capture());
    StepExecutionRequest sent = captor.getValue();

    assertThat(sent.tenantId()).isEqualTo("tenant-1");
    assertThat(sent.jobCode()).isEqualTo("job-import-1");
    assertThat(sent.stepCode()).isEqualTo("IMPORT"); // 跟 DefaultTaskExecutionWrapper 一致
    assertThat(sent.workerId()).isEqualTo("worker-7");
    assertThat(sent.context())
        .containsEntry("pipelineInstanceId", 42L)
        .containsEntry("traceId", "trace-abc");
  }

  @Test
  void successResponseMapsToTaskResultOk() {
    when(delegate.execute(any())).thenReturn(StepExecutionResponse.successResponse());

    TaskResult r = executor.execute(simpleCtx());

    assertThat(r.success()).isTrue();
    assertThat(r.message())
        .isEqualTo("ok"); // StepExecutionResponse.successResponse().message() == "ok"
  }

  @Test
  void failureResponsePropagatesMessage() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "PIPELINE_FAILED", "stage RECEIVE failed"));

    TaskResult r = executor.execute(simpleCtx());

    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("stage RECEIVE failed");
  }

  @Test
  void failureWithoutMessageFallsBackToCode() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "PIPELINE_FAILED", null));

    TaskResult r = executor.execute(simpleCtx());

    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("PIPELINE_FAILED");
  }

  private static TaskContext simpleCtx() {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", Map.of(), Map.of());
  }
}
