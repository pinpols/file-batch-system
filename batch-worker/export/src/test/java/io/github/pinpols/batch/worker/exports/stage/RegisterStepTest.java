package io.github.pinpols.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.worker.core.infrastructure.FileRecordParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.plugin.ExportDataPluginRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("导出登记阶段单测:文件记录新建,校验和冲突与复用绑定的语义")
class RegisterStepTest {

  private PlatformFileRecordRepository runtimeRepository;
  private PlatformPipelineRunRepository pipelineRuns;
  private PlatformFileAuditRepository fileAudits;
  private ExportDataPluginRegistry exportDataPluginRegistry;
  private ExportDataPlugin exportDataPlugin;
  private S3StorageProperties s3StorageProperties;
  private TaskExecutionClient taskExecutionClient;
  private RegisterStep step;
  private FileRecordParam capturedParam;

  @BeforeEach
  void setUp() {
    runtimeRepository = mock(PlatformFileRecordRepository.class);
    pipelineRuns = mock(PlatformPipelineRunRepository.class);
    fileAudits = mock(PlatformFileAuditRepository.class);
    exportDataPluginRegistry = mock(ExportDataPluginRegistry.class);
    exportDataPlugin = mock(ExportDataPlugin.class);
    s3StorageProperties = new S3StorageProperties();
    s3StorageProperties.setBucket("bucket-1");
    taskExecutionClient = mock(TaskExecutionClient.class);
    when(exportDataPlugin.registrationCallbackIdempotencyCapability())
        .thenReturn(IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC);
    step = new RegisterStep(
        runtimeRepository,
        pipelineRuns,
        fileAudits,
        exportDataPluginRegistry,
        s3StorageProperties,
        taskExecutionClient);
  }

  @Test
  @DisplayName("演练模式下跳过文件登记与插件回调,不产生任何仓储交互")
  void execute_dryRunSkipsFileRegistrationAndPluginCallback() {
    ExportJobContext context = baseContext();
    context.getAttributes().put(PipelineRuntimeKeys.DRY_RUN, true);
    context.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, "dry-run/no-upload");

    var result = step.execute(context);

    assertThat(result.success()).isTrue();
    verifyNoInteractions(runtimeRepository, exportDataPluginRegistry, exportDataPlugin);
  }

  @Test
  @DisplayName("对象名缺失时返回登记阶段非法,且不新建文件记录")
  void execute_returnsInvalid_whenObjectNameMissing() {
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_REGISTER_INVALID");
    verify(runtimeRepository, never()).createFileRecord(any(FileRecordParam.class));
  }

  @Test
  @DisplayName("同名文件已存在但校验和不一致时返回冲突,且不新建记录")
  void execute_returnsChecksumConflict_whenExistingFileRecordChecksumDiffers() {
    ExportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, "obj.json");
    ctx.getAttributes().put(PipelineRuntimeKeys.CHECKSUM_VALUE, "aaa");

    when(runtimeRepository.existsFileRecordByStoragePath("t1", "bucket-1", "obj.json"))
        .thenReturn(true);
    when(runtimeRepository.loadFileRecordByStoragePath("t1", "bucket-1", "obj.json"))
        .thenReturn(Map.of(
            "id", 1L, "checksum_value", "bbb", "metadata_json", Map.of("jobInstanceId", 101L)));

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_REGISTER_CHECKSUM_CONFLICT");
    verify(runtimeRepository, never()).createFileRecord(any(FileRecordParam.class));
  }

  @Test
  @DisplayName("新建文件记录时带上目标编码与换行等编码元数据")
  void execute_registersConfiguredCharsetAndEncodingMetadata() {
    ExportJobContext ctx = baseContext(Map.of("jobInstanceId", 999L));
    ctx.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, "obj.csv");
    ctx.getAttributes().put(PipelineRuntimeKeys.CHECKSUM_VALUE, "abc123");
    ctx.getAttributes().put("exportCharset", "GBK");
    ctx.getAttributes().put("exportLineSeparator", "\r\n");
    ctx.getAttributes().put("exportWithBom", Boolean.FALSE);
    ctx.getAttributes().put("exportDataRef", "jdbc_mapped_export");
    when(runtimeRepository.existsFileRecordByStoragePath("t1", "bucket-1", "obj.csv"))
        .thenReturn(false);
    when(exportDataPluginRegistry.require("jdbc_mapped_export")).thenReturn(exportDataPlugin);
    when(runtimeRepository.createFileRecord(any(FileRecordParam.class))).thenAnswer(invocation -> {
      capturedParam = invocation.getArgument(0);
      return 5L;
    });
    when(runtimeRepository.loadFileRecord("t1", 5L))
        .thenReturn(Map.of("id", 5L, "file_generation_no", 1));

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(capturedParam).isNotNull();
    assertThat(capturedParam.getCharset()).isEqualTo("GBK");
    assertThat(capturedParam.getMetadata())
        .asInstanceOf(MAP)
        .containsEntry("exportLineSeparator", "\r\n")
        .containsEntry("exportWithBom", false)
        .containsEntry("jobInstanceId", 101L);
  }

  @Test
  @DisplayName("校验和一致时复用已有文件记录,并绑定到管道实例且通知插件")
  void execute_reusesExistingFileRecord_whenChecksumMatches_andBindsToPipeline() {
    ExportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, "obj.json");
    ctx.getAttributes().put(PipelineRuntimeKeys.CHECKSUM_VALUE, "aaa");
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 99L);
    ctx.getAttributes().put("exportDataRef", "jdbc_mapped_export");

    when(runtimeRepository.existsFileRecordByStoragePath("t1", "bucket-1", "obj.json"))
        .thenReturn(true);
    when(runtimeRepository.loadFileRecordByStoragePath("t1", "bucket-1", "obj.json"))
        .thenReturn(Map.of(
            "id",
            1L,
            "checksum_value",
            "aaa",
            "file_generation_no",
            2,
            "metadata_json",
            Map.of("jobInstanceId", 101L)));
    when(exportDataPluginRegistry.require("jdbc_mapped_export")).thenReturn(exportDataPlugin);

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes()).containsEntry(PipelineRuntimeKeys.FILE_ID, 1L);
    verify(pipelineRuns).bindFileToPipelineInstance("t1", 99L, 1L);
    verify(exportDataPlugin).onRegistered(any(), anyLong(), eq(2), anyString());
    verifyNoInteractions(taskExecutionClient);
  }

  @Test
  @DisplayName("未知回调能力在调用插件前持久化非幂等执行围栏")
  void execute_fencesUnknownRegistrationCallbackBeforeInvokingPlugin() {
    ExportJobContext ctx = newArtifactContext();
    when(exportDataPlugin.registrationCallbackIdempotencyCapability())
        .thenReturn(IdempotencyCapability.UNKNOWN);
    when(taskExecutionClient.markNonIdempotentExecutionStarted("t1", 42L, "w1", "inv-1"))
        .thenReturn(true);

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    var ordered = inOrder(taskExecutionClient, exportDataPlugin);
    ordered.verify(taskExecutionClient).markNonIdempotentExecutionStarted("t1", 42L, "w1", "inv-1");
    ordered.verify(exportDataPlugin).onRegistered(any(), anyLong(), anyInt(), anyString());
  }

  @Test
  @DisplayName("已存在产物再次登记时,围栏未确认不得重复调用未知插件")
  void execute_refusesUnknownCallbackOnExistingArtifactWhenFenceIsNotConfirmed() {
    ExportJobContext ctx = existingArtifactContext();
    when(exportDataPlugin.registrationCallbackIdempotencyCapability())
        .thenReturn(IdempotencyCapability.NONE);
    when(taskExecutionClient.markNonIdempotentExecutionStarted("t1", 42L, "w1", "inv-1"))
        .thenReturn(false);

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
    verify(exportDataPlugin, never()).onRegistered(any(), anyLong(), anyInt(), anyString());
  }

  @Test
  @DisplayName("执行围栏无法确认时拒绝调用未知导出回调")
  void execute_refusesUnknownRegistrationCallbackWhenFenceIsNotConfirmed() {
    ExportJobContext ctx = existingArtifactContext();
    when(exportDataPlugin.registrationCallbackIdempotencyCapability())
        .thenReturn(IdempotencyCapability.UNKNOWN);
    when(taskExecutionClient.markNonIdempotentExecutionStarted("t1", 42L, "w1", "inv-1"))
        .thenReturn(false);

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
    verify(exportDataPlugin, never()).onRegistered(any(), anyLong(), anyInt(), anyString());
  }

  private ExportJobContext baseContext() {
    return baseContext(Map.of());
  }

  private ExportJobContext baseContext(Map<String, Object> metadata) {
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setJobCode("JOB_001");
    ctx.setWorkerId("w1");
    ctx.getAttributes().put(PipelineRuntimeKeys.JOB_INSTANCE_ID, 101L);
    ctx.getAttributes().put(PipelineRuntimeKeys.FILE_NAME, "f.json");
    ctx.getAttributes().put(PipelineRuntimeKeys.EXPORT_FILE_FORMAT_TYPE, "JSON");
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
                metadata));
    ctx.getAttributes().put("exportBatch", Map.of("id", 10L));
    ctx.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "trace-1");
    return ctx;
  }

  private ExportJobContext existingArtifactContext() {
    ExportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, "obj.json");
    ctx.getAttributes().put(PipelineRuntimeKeys.CHECKSUM_VALUE, "aaa");
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 99L);
    ctx.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 42L);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "inv-1");
    ctx.getAttributes().put("exportDataRef", "jdbc_mapped_export");
    when(runtimeRepository.existsFileRecordByStoragePath("t1", "bucket-1", "obj.json"))
        .thenReturn(true);
    when(runtimeRepository.loadFileRecordByStoragePath("t1", "bucket-1", "obj.json"))
        .thenReturn(Map.of(
            "id",
            1L,
            "checksum_value",
            "aaa",
            "file_generation_no",
            2,
            "metadata_json",
            Map.of("jobInstanceId", 101L)));
    when(exportDataPluginRegistry.require("jdbc_mapped_export")).thenReturn(exportDataPlugin);
    return ctx;
  }

  private ExportJobContext newArtifactContext() {
    ExportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, "obj.json");
    ctx.getAttributes().put(PipelineRuntimeKeys.CHECKSUM_VALUE, "aaa");
    ctx.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 42L);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "inv-1");
    ctx.getAttributes().put("exportDataRef", "jdbc_mapped_export");
    when(runtimeRepository.existsFileRecordByStoragePath("t1", "bucket-1", "obj.json"))
        .thenReturn(false);
    when(exportDataPluginRegistry.require("jdbc_mapped_export")).thenReturn(exportDataPlugin);
    when(runtimeRepository.createFileRecord(any(FileRecordParam.class))).thenReturn(5L);
    when(runtimeRepository.loadFileRecord("t1", 5L))
        .thenReturn(Map.of("id", 5L, "file_generation_no", 1));
    return ctx;
  }
}
