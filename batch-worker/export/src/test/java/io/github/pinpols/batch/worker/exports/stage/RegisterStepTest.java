package io.github.pinpols.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.worker.core.infrastructure.FileRecordParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.plugin.ExportDataPluginRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegisterStepTest {

  private PlatformFileRuntimeRepository runtimeRepository;
  private ExportDataPluginRegistry exportDataPluginRegistry;
  private ExportDataPlugin exportDataPlugin;
  private S3StorageProperties s3StorageProperties;
  private RegisterStep step;

  @BeforeEach
  void setUp() {
    runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    exportDataPluginRegistry = mock(ExportDataPluginRegistry.class);
    exportDataPlugin = mock(ExportDataPlugin.class);
    s3StorageProperties = new S3StorageProperties();
    s3StorageProperties.setBucket("bucket-1");
    step = new RegisterStep(runtimeRepository, exportDataPluginRegistry, s3StorageProperties);
  }

  @Test
  void execute_returnsInvalid_whenObjectNameMissing() {
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_REGISTER_INVALID");
    verify(runtimeRepository, never()).createFileRecord(any(FileRecordParam.class));
  }

  @Test
  void execute_returnsChecksumConflict_whenExistingFileRecordChecksumDiffers() {
    ExportJobContext ctx = baseContext();
    ctx.getAttributes().put("objectName", "obj.json");
    ctx.getAttributes().put("checksumValue", "aaa");

    when(runtimeRepository.existsFileRecordByStoragePath(eq("t1"), eq("bucket-1"), eq("obj.json")))
        .thenReturn(true);
    when(runtimeRepository.loadFileRecordByStoragePath(eq("t1"), eq("bucket-1"), eq("obj.json")))
        .thenReturn(Map.of("id", 1L, "checksum_value", "bbb"));

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_REGISTER_CHECKSUM_CONFLICT");
    verify(runtimeRepository, never()).createFileRecord(any(FileRecordParam.class));
  }

  @Test
  void execute_reusesExistingFileRecord_whenChecksumMatches_andBindsToPipeline() {
    ExportJobContext ctx = baseContext();
    ctx.getAttributes().put("objectName", "obj.json");
    ctx.getAttributes().put("checksumValue", "aaa");
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 99L);
    ctx.getAttributes().put("exportDataRef", "jdbc_mapped_export");

    when(runtimeRepository.existsFileRecordByStoragePath(eq("t1"), eq("bucket-1"), eq("obj.json")))
        .thenReturn(true);
    when(runtimeRepository.loadFileRecordByStoragePath(eq("t1"), eq("bucket-1"), eq("obj.json")))
        .thenReturn(Map.of("id", 1L, "checksum_value", "aaa", "file_generation_no", 2));
    when(runtimeRepository.toLong(eq(1L))).thenReturn(1L);
    when(runtimeRepository.toLong(eq(99L))).thenReturn(99L);
    when(runtimeRepository.toLong(eq(10L))).thenReturn(10L);
    when(exportDataPluginRegistry.require(eq("jdbc_mapped_export"))).thenReturn(exportDataPlugin);

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes().get(PipelineRuntimeKeys.FILE_ID)).isEqualTo(1L);
    verify(runtimeRepository).bindFileToPipelineInstance(eq(99L), eq(1L));
    verify(exportDataPlugin).onRegistered(any(), anyLong(), eq(2), anyString());
  }

  private ExportJobContext baseContext() {
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setJobCode("JOB_001");
    ctx.setWorkerId("w1");
    ctx.getAttributes().put("fileName", "f.json");
    ctx.getAttributes().put("exportFileFormatType", "JSON");
    ctx.getAttributes()
        .put(
            "exportPayload",
            new ExportPayload(
                "FC1",
                "BIZ",
                "TPL_1",
                "B001",
                "f.json",
                null,
                "2026-03-25",
                null,
                Boolean.FALSE,
                null,
                Map.of()));
    ctx.getAttributes().put("exportBatch", Map.of("id", 10L));
    ctx.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "trace-1");
    return ctx;
  }
}
