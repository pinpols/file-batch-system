package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.WorkerConfigException;
import io.github.pinpols.batch.common.plugin.ImportLoadContext;
import io.github.pinpols.batch.common.plugin.ImportLoadPlugin;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration.FileProcessing;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.plugin.ImportLoadPluginRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 单测：LoadStep —— 流式路径 / dry-run / 插件配置错误 / 全跳过 等关键分支。 不重复测 AbstractPipelineStepExecutionAdapter
 * 的通用治理逻辑。
 */
@ExtendWith(MockitoExtension.class)
class LoadStepTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;
  @Mock private ImportLoadPlugin plugin;

  private ImportLoadPluginRegistry registry;
  private ImportWorkerConfiguration workerConfig;
  private ObjectMapper objectMapper;
  private LoadStep loadStep;
  @TempDir Path tempDir;

  private final List<Path> tempPaths = new ArrayList<>();

  @BeforeEach
  void setUp() {
    when(plugin.id()).thenReturn(WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    registry = new ImportLoadPluginRegistry(List.of(plugin));
    workerConfig =
        new ImportWorkerConfiguration(
            "wc",
            "wt",
            "tenant",
            5_000L,
            "topic",
            "cg",
            List.of(),
            new FileProcessing(true, 1000, 1000, 2),
            Boolean.FALSE);
    objectMapper = new ObjectMapper();
    loadStep =
        new LoadStep(
            registry,
            runtimeRepository,
            workerConfig,
            objectMapper,
            new io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties(),
            null);
  }

  @AfterEach
  void cleanup() throws Exception {
    for (Path p : tempPaths) {
      Files.deleteIfExists(p);
    }
  }

  // ── stage() ──

  @Test
  void shouldReturnLoadStage() {
    assertThat(loadStep.stage()).isEqualTo(ImportStage.LOAD);
  }

  // ── streaming happy path ──

  @Test
  void shouldStreamingLoad_andCleanupValidatedFile_whenSuccess() throws Exception {
    Path validated = writeNdjson(List.of(row("C001"), row("C002"), row("C003")));
    Path parsed = writeNdjson(List.of(row("C001")));

    ImportJobContext ctx = streamingContext(validated, parsed);
    when(runtimeRepository.toLong(any())).thenReturn(99L);
    when(plugin.loadChunk(any(), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    // 3 rows / chunkSize=2 → 2 次 flush
    verify(plugin, times(2)).loadChunk(any(ImportLoadContext.class), any());
    assertThat(ctx.getAttributes()).containsEntry("loadedCount", 3L);
    assertThat(ctx.getAttributes()).containsEntry("successCount", 3L);
    verify(runtimeRepository).updateFileStatus(eq(99L), eq("LOADED"), any());
    // M-5: 成功路径删暂存文件
    assertThat(Files.exists(validated)).isFalse();
    assertThat(Files.exists(parsed)).isFalse();
  }

  @Test
  void shouldKeepValidatedFile_whenPluginThrows() throws Exception {
    Path validated = writeNdjson(List.of(row("C001"), row("C002")));
    ImportJobContext ctx = streamingContext(validated, null);
    when(plugin.loadChunk(any(), any())).thenThrow(new RuntimeException("kaboom"));

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_LOAD_FAILED");
    // M-5: 失败时保留暂存文件
    assertThat(Files.exists(validated)).isTrue();
  }

  @Test
  void shouldReturnConfigInvalid_whenWorkerConfigExceptionRaised() throws Exception {
    Path validated = writeNdjson(List.of(row("C001")));
    ImportJobContext ctx = streamingContext(validated, null);
    when(plugin.loadChunk(any(), any())).thenThrow(new WorkerConfigException("bad cfg"));

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_LOAD_CONFIG_INVALID");
  }

  @Test
  void shouldShortCircuit_whenAllRecordsSkipped() throws Exception {
    // skippedCount > 0 且 validated 文件为空 → 直接 markLoaded(0)，不调 plugin
    Path validated = Files.createTempFile("validated-", ".ndjson");
    tempPaths.add(validated);
    ImportJobContext ctx = streamingContext(validated, null);
    ctx.getAttributes().put("skippedCount", 5L);
    when(runtimeRepository.toLong(any())).thenReturn(99L);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes()).containsEntry("loadedCount", 0L);
    verify(plugin, never()).loadChunk(any(), any());
    verify(runtimeRepository).updateFileStatus(eq(99L), eq("LOADED"), any());
  }

  @Test
  void shouldFailNoPayload_whenValidatedPathSetButFileMissing() {
    ImportJobContext ctx = streamingContext(tempDir.resolve("ghost.ndjson"), null);
    // streaming path: validated 文件不存在 → IMPORT_LOAD_NO_PAYLOAD(ADR-038 P3 legacy 已下线)
    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_LOAD_NO_PAYLOAD");
  }

  @Test
  void shouldFailNoPayload_whenValidatedPathMissing() {
    ImportJobContext ctx = baseContext();

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_LOAD_NO_PAYLOAD");
  }

  // ── dry-run ──

  @Test
  void shouldDryRun_estimateLoadedCountFromValidatedFile() throws Exception {
    Path validated = writeNdjson(List.of(row("C1"), row("C2"), row("C3")));
    ImportJobContext ctx = streamingContext(validated, null);
    ctx.getAttributes().put(PipelineRuntimeKeys.DRY_RUN, true);
    when(runtimeRepository.toLong(any())).thenReturn(99L);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes()).containsEntry("loadedCount", 3L);
    verify(plugin, never()).loadChunk(any(), any());
    verify(runtimeRepository).updateFileStatus(eq(99L), eq("LOADED"), any());
  }

  @Test
  void shouldDryRun_returnZero_whenValidatedPathMissing() {
    ImportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.DRY_RUN, true);
    when(runtimeRepository.toLong(any())).thenReturn(99L);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes()).containsEntry("loadedCount", 0L);
  }

  // ── plugin resolution ──

  @Test
  void shouldUseLoadTargetRefFromTemplateConfig() throws Exception {
    ImportLoadPlugin custom = org.mockito.Mockito.mock(ImportLoadPlugin.class);
    when(custom.id()).thenReturn("custom_plugin");
    when(plugin.id()).thenReturn(WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    registry = new ImportLoadPluginRegistry(List.of(plugin, custom));
    loadStep =
        new LoadStep(
            registry,
            runtimeRepository,
            workerConfig,
            objectMapper,
            new io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties(),
            null);

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated, null);
    ctx.getAttributes()
        .put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("load_target_ref", "custom_plugin"));

    when(runtimeRepository.toLong(any())).thenReturn(99L);
    when(custom.loadChunk(any(), any())).thenReturn(1);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    verify(custom).loadChunk(any(), any());
    verify(plugin, never()).loadChunk(any(), any());
  }

  @Test
  void shouldBuildLoadContext_withImportPayloadFields() throws Exception {
    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated, null);
    ImportPayload payload = mockPayload();
    when(payload.batchNo()).thenReturn("BATCH-42");
    when(payload.bizType()).thenReturn("CUST");
    when(payload.templateCode()).thenReturn("T1");
    ctx.getAttributes().put("importPayload", payload);
    when(runtimeRepository.toLong(any())).thenReturn(99L);
    when(plugin.loadChunk(any(), any())).thenReturn(1);

    loadStep.execute(ctx);

    ArgumentCaptor<ImportLoadContext> captor = ArgumentCaptor.forClass(ImportLoadContext.class);
    verify(plugin).loadChunk(captor.capture(), any());
    ImportLoadContext loadCtx = captor.getValue();
    assertThat(loadCtx.batchNo()).isEqualTo("BATCH-42");
    assertThat(loadCtx.bizType()).isEqualTo("CUST");
    assertThat(loadCtx.templateCode()).isEqualTo("T1");
  }

  @Test
  void shouldUseFileRecordFileName_whenAvailable() throws Exception {
    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated, null);
    ctx.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, Map.of("file_name", "src.csv"));
    when(runtimeRepository.toLong(any())).thenReturn(99L);
    when(plugin.loadChunk(any(), any())).thenReturn(1);

    loadStep.execute(ctx);

    ArgumentCaptor<ImportLoadContext> captor = ArgumentCaptor.forClass(ImportLoadContext.class);
    verify(plugin).loadChunk(captor.capture(), any());
    assertThat(captor.getValue().sourceFileName()).isEqualTo("src.csv");
  }

  // ── helpers ──

  private ImportPayload mockPayload() {
    return org.mockito.Mockito.mock(ImportPayload.class);
  }

  private ImportJobContext baseContext() {
    ImportJobContext ctx = new ImportJobContext();
    ctx.setTenantId("tenant-A");
    ctx.setJobCode("JOB");
    ctx.setBizDate("2026-05-24");
    ctx.setWorkerId("w1");
    ctx.setFileId("99");
    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.FILE_ID, 99L);
    ctx.setAttributes(attrs);
    return ctx;
  }

  private ImportJobContext streamingContext(Path validated, Path parsed) {
    ImportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH, validated.toString());
    if (parsed != null) {
      ctx.getAttributes().put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, parsed.toString());
    }
    return ctx;
  }

  private Path writeNdjson(List<Map<String, Object>> rows) throws Exception {
    Path file = Files.createTempFile("validated-", ".ndjson");
    tempPaths.add(file);
    StringBuilder sb = new StringBuilder();
    for (Map<String, Object> row : rows) {
      sb.append(objectMapper.writeValueAsString(row)).append('\n');
    }
    Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    return file;
  }

  private Map<String, Object> row(String cust) {
    Map<String, Object> m = new HashMap<>();
    m.put("customerNo", cust);
    m.put("customerName", "name-" + cust);
    return m;
  }
}
