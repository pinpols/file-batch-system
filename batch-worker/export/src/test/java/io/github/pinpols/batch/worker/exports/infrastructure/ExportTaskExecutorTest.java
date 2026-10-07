package io.github.pinpols.batch.worker.exports.infrastructure;

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

/** {@link ExportTaskExecutor} 单测 — 跟 {@code ImportTaskExecutorTest} 同 pattern。 */
@DisplayName("导出任务执行器单测:任务类型,资源能力与请求翻译及失败消息回退语义")
class ExportTaskExecutorTest {

  private ExportStepExecutionAdapter delegate;
  private ExportTaskExecutor executor;

  @BeforeEach
  void setUp() {
    delegate = mock(ExportStepExecutionAdapter.class);
    executor = new ExportTaskExecutor(delegate);
  }

  @Test
  @DisplayName("读取任务类型时返回导出类型")
  void shouldReportExportTaskType_whenReadingTaskType() {
    assertThat(executor.taskType()).isEqualTo("EXPORT");
  }

  @Test
  @DisplayName("能力声明包含数据库,磁盘与网络资源,且可取消但非幂等")
  void shouldDeclareResourcesAndCapabilities_whenReadingCapability() {
    assertThat(executor.capability().resourceKinds())
        .contains(ResourceKind.DB, ResourceKind.DISK, ResourceKind.NET);
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
  }

  @Test
  @DisplayName("执行时把任务上下文翻译成步骤请求,作业与租户字段正确透传")
  void shouldTranslateContextIntoStepRequest_whenExecuting() {
    when(delegate.execute(any())).thenReturn(StepExecutionResponse.successResponse());

    TaskContext ctx = new TaskContext(
        "tenant-1",
        "job-export-1",
        "ti-9",
        "worker-7",
        Map.of(),
        Map.of(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 42L));

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
  @DisplayName("委托返回失败时,失败消息原样透传到任务结果")
  void shouldPropagateMessage_whenDelegateFails() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "PIPELINE_FAILED", "stage QUERY failed"));

    TaskResult r = executor.execute(simpleCtx());

    assertThat(r.success()).isFalse();
    assertThat(r.message()).isEqualTo("stage QUERY failed");
  }

  @Test
  @DisplayName("失败但没有消息时,错误码作为任务结果消息")
  void shouldFallBackToCode_whenFailureMessageMissing() {
    when(delegate.execute(any()))
        .thenReturn(new StepExecutionResponse(false, "EXPORT_FAILED", null));
    TaskResult r = executor.execute(simpleCtx());
    assertThat(r.message()).isEqualTo("EXPORT_FAILED");
  }

  private static TaskContext simpleCtx() {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", Map.of(), Map.of());
  }
}
