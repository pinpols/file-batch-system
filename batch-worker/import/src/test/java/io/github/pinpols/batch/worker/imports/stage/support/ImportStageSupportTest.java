package io.github.pinpols.batch.worker.imports.stage.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("导入阶段支撑工具单测:分块大小上限与文件状态恢复感知更新的语义")
class ImportStageSupportTest {

  @Test
  @DisplayName("模板分块大小在上限内时,按模板值生效")
  void shouldUseTemplateChunkSize_whenWithinMax() {
    ImportJobContext context = new ImportJobContext();
    context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("chunk_size", 5000));
    ImportWorkerConfiguration config = config(2000, 10000);

    assertThat(ImportStageSupport.resolveChunkSize(context, config)).isEqualTo(5000);
  }

  @Test
  @DisplayName("模板分块大小超过上限时抛参数非法,并提示超限")
  void shouldRejectTemplateChunkSize_whenAboveMax() {
    ImportJobContext context = new ImportJobContext();
    context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("chunk_size", 20000));
    ImportWorkerConfiguration config = config(2000, 10000);

    assertThatThrownBy(() -> ImportStageSupport.resolveChunkSize(context, config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunk_size exceeds maxChunkSize");
  }

  @Test
  @DisplayName("未配置模板且回退值超过上限时,同样抛参数非法")
  void shouldRejectFallbackChunkSize_whenAboveMax() {
    assertThatThrownBy(
            () -> ImportStageSupport.resolveChunkSize(new ImportJobContext(), config(20000, 10000)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunk_size exceeds maxChunkSize");
  }

  @Test
  @DisplayName("多分区导入遇到状态冲突时读取当前状态后放行,不抛异常")
  void shouldIgnoreStatusConflict_whenImportIsPartitioned() {
    PlatformFileRecordRepository repository = mock(PlatformFileRecordRepository.class);
    ImportJobContext context = context(99L);
    context.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 2);
    doThrow(stateConflict())
        .when(repository)
        .updateFileStatus(eq(99L), eq(FileStatus.PARSING.code()), any());
    when(repository.currentFileStatus(99L)).thenReturn(FileStatus.PARSED.code());

    ImportStageSupport.updateFileStatusRecoverAware(
        repository, context, FileStatus.PARSING.code(), Map.of());

    verify(repository).currentFileStatus(99L);
  }

  @Test
  @DisplayName("非分区导入遇到状态冲突时保持严格状态机,抛业务异常")
  void shouldKeepStrictStateMachine_whenImportIsNotPartitioned() {
    PlatformFileRecordRepository repository = mock(PlatformFileRecordRepository.class);
    ImportJobContext context = context(99L);
    doThrow(stateConflict())
        .when(repository)
        .updateFileStatus(eq(99L), eq(FileStatus.PARSING.code()), any());

    assertThatThrownBy(() -> ImportStageSupport.updateFileStatusRecoverAware(
            repository, context, FileStatus.PARSING.code(), Map.of()))
        .isInstanceOf(BizException.class)
        .hasMessage("error.common.state_conflict_detail");
  }

  @Test
  @DisplayName("多分区导入但当前状态落后于目标状态时,仍抛状态冲突异常")
  void shouldRejectConflict_whenPartitionedStatusBehindTarget() {
    PlatformFileRecordRepository repository = mock(PlatformFileRecordRepository.class);
    ImportJobContext context = context(99L);
    context.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 2);
    doThrow(stateConflict())
        .when(repository)
        .updateFileStatus(eq(99L), eq(FileStatus.PARSED.code()), any());
    when(repository.currentFileStatus(99L)).thenReturn(FileStatus.PARSING.code());

    assertThatThrownBy(() -> ImportStageSupport.updateFileStatusRecoverAware(
            repository, context, FileStatus.PARSED.code(), Map.of()))
        .isInstanceOf(BizException.class)
        .hasMessage("error.common.state_conflict_detail");
  }

  private static ImportJobContext context(Long fileId) {
    ImportJobContext context = new ImportJobContext();
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, fileId);
    return context;
  }

  private static BizException stateConflict() {
    return BizException.of(
        ResultCode.STATE_CONFLICT,
        "error.common.state_conflict_detail",
        "illegal file status transition");
  }

  private static ImportWorkerConfiguration config(int chunkSize, int maxChunkSize) {
    return new ImportWorkerConfiguration(
        "w",
        "IMPORT",
        "tenant-a",
        1000L,
        "topic",
        "group",
        List.of(),
        new ImportWorkerConfiguration.FileProcessing(true, 1000, 1000, chunkSize, maxChunkSize),
        false);
  }
}
