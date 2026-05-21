package com.example.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Import FEEDBACK 阶段单测：审计聚合 / dry-run 跳过 / null 上下文兜底。 */
@ExtendWith(MockitoExtension.class)
class FeedbackStepTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  @InjectMocks private FeedbackStep step;

  @Test
  void shouldReturnStageFeedback_whenStageAccessed() {
    assertThat(step.stage()).isEqualTo(ImportStage.FEEDBACK);
  }

  @Test
  @DisplayName("happy path: 汇总 parsed/validated/loaded 三段计数 + pipelineInstanceId 写 audit")
  void shouldAggregateCountsIntoAudit_whenAllAttributesPresent() {
    // arrange
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("tenant-1");
    context.setWorkerId("worker-A");
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, "1001");
    context.getAttributes().put("parsedCount", 100L);
    context.getAttributes().put("validatedCount", 95L);
    context.getAttributes().put("loadedCount", 90L);
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 7L);
    context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "trace-xyz");
    when(runtimeRepository.toLong(any())).thenReturn(1001L);

    // act
    ImportStageResult result = step.execute(context);

    // assert
    assertThat(result.success()).isTrue();
    ArgumentCaptor<FileAuditParam> captor = ArgumentCaptor.forClass(FileAuditParam.class);
    verify(runtimeRepository).appendAudit(captor.capture());
    FileAuditParam audit = captor.getValue();
    assertThat(audit.getFileId()).isEqualTo(1001L);
    assertThat(audit.getTenantId()).isEqualTo("tenant-1");
    assertThat(audit.getOperationType()).isEqualTo("IMPORT_FEEDBACK");
    assertThat(audit.getOperationResult()).isEqualTo("SUCCESS");
    assertThat(audit.getOperatorType()).isEqualTo("SYSTEM");
    assertThat(audit.getOperatorId()).isEqualTo("worker-A");
    assertThat(audit.getTraceId()).isEqualTo("trace-xyz");
    assertThat(audit.getDetailSummary()).asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("parsedCount", 100L)
        .containsEntry("validatedCount", 95L)
        .containsEntry("loadedCount", 90L)
        .containsEntry("pipelineInstanceId", 7L);
  }

  @Test
  @DisplayName("dry-run 模式：直接返回 success，不写 audit / 不调 runtimeRepository")
  void shouldSkipAudit_whenDryRunFlagSet() {
    ImportJobContext context = new ImportJobContext();
    context.getAttributes().put("dryRun", Boolean.TRUE);

    ImportStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verifyNoInteractions(runtimeRepository);
  }

  @Test
  @DisplayName("缺失 parsed/validated/loaded 等指标时：仍写 audit，detailSummary 字段含 null 值")
  void shouldStillWriteAudit_whenMetricsMissing() {
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("t-x");
    context.setWorkerId("w-x");
    when(runtimeRepository.toLong(any())).thenReturn(null);

    ImportStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    ArgumentCaptor<FileAuditParam> captor = ArgumentCaptor.forClass(FileAuditParam.class);
    verify(runtimeRepository).appendAudit(captor.capture());
    assertThat(captor.getValue().getFileId()).isNull();
    assertThat(captor.getValue().getDetailSummary())
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsKeys("parsedCount", "validatedCount", "loadedCount", "pipelineInstanceId");
  }

  @Test
  @DisplayName("dryRun=\"true\" 字符串值同样被识别为 dry-run（DryRunGuard 兼容字符串）")
  void shouldSkipAudit_whenDryRunStringTrue() {
    ImportJobContext context = new ImportJobContext();
    context.getAttributes().put("dryRun", "true");

    ImportStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    verify(runtimeRepository, never()).appendAudit(any());
  }
}
