package io.github.pinpols.batch.worker.imports.stage.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportStageSupportTest {

  @Test
  void resolveChunkSizeUsesTemplateValueWithinMax() {
    ImportJobContext context = new ImportJobContext();
    context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("chunk_size", 5000));
    ImportWorkerConfiguration config = config(2000, 10000);

    assertThat(ImportStageSupport.resolveChunkSize(context, config)).isEqualTo(5000);
  }

  @Test
  void resolveChunkSizeRejectsTemplateValueAboveMax() {
    ImportJobContext context = new ImportJobContext();
    context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("chunk_size", 20000));
    ImportWorkerConfiguration config = config(2000, 10000);

    assertThatThrownBy(() -> ImportStageSupport.resolveChunkSize(context, config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunk_size exceeds maxChunkSize");
  }

  @Test
  void resolveChunkSizeRejectsFallbackAboveMax() {
    assertThatThrownBy(
            () -> ImportStageSupport.resolveChunkSize(new ImportJobContext(), config(20000, 10000)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunk_size exceeds maxChunkSize");
  }

  @Test
  void updateFileStatusRecoverAwareSkipsRollbackConflictForPartitionedImport() {
    PlatformFileRuntimeRepository repository = mock(PlatformFileRuntimeRepository.class);
    ImportJobContext context = context(99L);
    context.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 2);
    when(repository.toLong(99L)).thenReturn(99L);
    doThrow(stateConflict()).when(repository).updateFileStatus(eq(99L), eq("PARSING"), any());
    when(repository.currentFileStatus(99L)).thenReturn("PARSED");

    ImportStageSupport.updateFileStatusRecoverAware(repository, context, "PARSING", Map.of());

    verify(repository).currentFileStatus(99L);
  }

  @Test
  void updateFileStatusRecoverAwareKeepsStrictStateMachineForNormalImport() {
    PlatformFileRuntimeRepository repository = mock(PlatformFileRuntimeRepository.class);
    ImportJobContext context = context(99L);
    when(repository.toLong(99L)).thenReturn(99L);
    doThrow(stateConflict()).when(repository).updateFileStatus(eq(99L), eq("PARSING"), any());

    assertThatThrownBy(
            () ->
                ImportStageSupport.updateFileStatusRecoverAware(
                    repository, context, "PARSING", Map.of()))
        .isInstanceOf(BizException.class)
        .hasMessage("error.common.state_conflict_detail");
  }

  @Test
  void updateFileStatusRecoverAwareRejectsPartitionConflictWhenCurrentStatusIsBehindTarget() {
    PlatformFileRuntimeRepository repository = mock(PlatformFileRuntimeRepository.class);
    ImportJobContext context = context(99L);
    context.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 2);
    when(repository.toLong(99L)).thenReturn(99L);
    doThrow(stateConflict()).when(repository).updateFileStatus(eq(99L), eq("PARSED"), any());
    when(repository.currentFileStatus(99L)).thenReturn("PARSING");

    assertThatThrownBy(
            () ->
                ImportStageSupport.updateFileStatusRecoverAware(
                    repository, context, "PARSED", Map.of()))
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
