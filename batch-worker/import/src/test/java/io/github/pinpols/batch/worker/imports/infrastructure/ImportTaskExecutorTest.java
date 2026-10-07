package io.github.pinpols.batch.worker.imports.infrastructure;

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
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link ImportTaskExecutor} 单测 — 验证 SPI 包装路径跟直调 ImportStepExecutionAdapter **行为等价**。
 *
 * <p>不引 Spring context,直接构造 + mock delegate。Pipeline lifecycle 行为本身由
 * AbstractPipelineStepExecutionAdapterTest 覆盖,本测试只关心包装层翻译是否正确。
 */
@DisplayName("导入任务执行器单测:任务类型,资源能力与请求翻译及失败消息回退语义")
class ImportTaskExecutorTest {

  private ImportStepExecutionAdapter delegate;
  private ImportTaskExecutor executor;

  @BeforeEach
  void setUp() {
    delegate = mock(ImportStepExecutionAdapter.class);
    executor = new ImportTaskExecutor(delegate);
  }

  @Test
  @DisplayName("读取任务类型时返回导入类型")
  void shouldReportImportTaskType_whenReadingTaskType() {
    assertThat(executor.taskType()).isEqualTo("IMPORT");
  }

  @Test
  @DisplayName("能力声明包含磁盘,数据库与网络资源,且可取消但非幂等")
  void shouldDeclareResourcesAndCapabilities_whenReadingCapability() {
    assertThat(executor.capability().resourceKinds())
        .contains(ResourceKind.DISK, ResourceKind.DB, ResourceKind.NET);
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
  }

  @Test
  @DisplayName("执行时翻译步骤请求,租户,作业,执行器与追踪字段全部保留")
  void shouldTranslateContextPreservingFields_whenExecuting() {
    when(delegate.execute(any())).thenReturn(StepExecutionResponse.successResponse());

    TaskContext ctx = new TaskContext(
        "tenant-1",
        "job-import-1",
        "ti-9",
        "worker-7",
        Map.of(), // SPI parameters 不映射(Phase 3 不做),走 runtimeAttributes
        Map.of(
            PipelineRuntimeKeys.PIPELINE_INSTANCE_ID,
            42L,
            PipelineRuntimeKeys.TRACE_ID,
            "trace-abc"));

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
        .containsEntry(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 42L)
        .containsEntry(PipelineRuntimeKeys.TRACE_ID, "trace-abc");
  }

  @Test
  @DisplayName("委托返回成功时,任务结果标记成功并沿用其消息")
  void shouldMapSuccessResponse_whenDelegateSucceeds() {
    when(delegate.execute(any())).thenReturn(StepExecutionResponse.successResponse());

    TaskResult r = executor.execute(simpleCtx());

    assertThat(r.success()).isTrue();
    assertThat(r.message())
        .isEqualTo("ok"); // StepExecutionResponse.successResponse().message() == "ok"
  }

  @Test
  @DisplayName("委托返回失败时,失败消息原样透传到任务结果")
  void shouldPropagateMessage_whenDelegateFails() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "PIPELINE_FAILED", "stage RECEIVE failed"));

    TaskResult r = executor.execute(simpleCtx());

    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("stage RECEIVE failed");
  }

  @Test
  @DisplayName("失败但没有消息时,错误码作为任务结果消息")
  void shouldFallBackToCode_whenFailureMessageMissing() {
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
