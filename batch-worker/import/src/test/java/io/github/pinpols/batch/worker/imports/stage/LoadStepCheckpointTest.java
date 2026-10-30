package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ImportLoadPlugin;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineStageProgressRegistry;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPosition;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingStage;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration.FileProcessing;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.plugin.ImportLoadPluginRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-038 P2 续跑位点行为单测 — 补 {@link LoadStepTest} 之外的开关开 / 开关关 / 续跑 / 已完成跳过 等关键分支。
 *
 * <p>共享原测的 fixture 风格(@TempDir / 不 transactional / mock plugin)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("导入续跑位点行为单测:开关,幂等跳过与多分区降级语义")
class LoadStepCheckpointTest {

  private static final String TENANT = "tenant-A";
  private static final long PIPELINE_INSTANCE_ID = 7001L;

  @Mock
  private PlatformFileRecordRepository runtimeRepository;

  @Mock
  private ImportLoadPlugin plugin;

  @Mock
  private ProcessingPositionStore positionStore;

  @Mock
  private TaskExecutionClient taskExecutionClient;

  private ImportLoadPluginRegistry registry;
  private ImportWorkerConfiguration workerConfig;
  private ObjectMapper objectMapper;
  private WorkerCheckpointProperties checkpointProps;
  private LoadStep loadStep;

  private final List<Path> tempPaths = new ArrayList<>();

  @BeforeEach
  void setUp() {
    when(plugin.id()).thenReturn(WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    // ADR-038 R3-3:续跑开关开时 LoadStep 会校验 plugin 幂等能力;mock 默认返回 null,需显式 stub。
    // disabled 路径不会触发(不调 idempotencyCapability),用 lenient 避免 UnnecessaryStubbing。
    Mockito.lenient()
        .when(plugin.idempotencyCapability())
        .thenReturn(IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT);
    registry = new ImportLoadPluginRegistry(List.of(plugin));
    workerConfig = new ImportWorkerConfiguration(
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
    checkpointProps = new WorkerCheckpointProperties();
    loadStep = new LoadStep(
        registry,
        runtimeRepository,
        workerConfig,
        objectMapper,
        checkpointProps,
        positionStore,
        new PipelineStageProgressRegistry(),
        taskExecutionClient);
  }

  @AfterEach
  void cleanup() throws Exception {
    for (Path p : tempPaths) {
      Files.deleteIfExists(p);
    }
  }

  @Test
  @DisplayName("开关关时:既不读位点也不写位点(行为与未引入本特性一致)")
  void checkpointDisabled_skipsPositionStore() throws Exception {
    checkpointProps.setEnabled(false);
    Path validated = writeNdjson(List.of(row("C1"), row("C2"), row("C3")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    when(plugin.loadChunk(any(), any())).thenReturn(2, 1);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    verify(positionStore, never()).load(any(), anyLong(), any());
    verify(positionStore, never()).advance(any(), anyLong(), any(), any(), anyLong());
    verify(positionStore, never()).markCompleted(any(), anyLong(), any());
  }

  @Test
  @DisplayName("开关开 + completed=true:幂等跳过 loadChunk,直接 markLoaded")
  void checkpointCompleted_skipsLoadChunk() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(new ProcessingPosition(marker(validated, 2L), 2L, true));

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes().get(ImportRuntimeKeys.SUCCESS_COUNT)).isEqualTo(2L);
    verify(plugin, never()).loadChunk(any(), any());
    verify(positionStore, never()).advance(any(), anyLong(), any(), any(), anyLong());
    verify(positionStore, never()).markCompleted(any(), anyLong(), any()); // 已 completed 不重复标记
  }

  @Test
  @DisplayName("已完成位点恢复时状态写入失败，保留暂存文件供重试")
  void completedCheckpoint_statusFailurePreservesValidatedFile() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(new ProcessingPosition(marker(validated, 2L), 2L, true));
    Mockito.doThrow(new IllegalStateException("status unavailable"))
        .doNothing()
        .when(runtimeRepository)
        .updateFileStatus(eq(99L), eq("LOADED"), any());

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(Files.exists(validated)).isTrue();
    ImportStageResult retry = loadStep.execute(ctx);
    assertThat(retry.success()).isTrue();
    assertThat(ctx.getAttributes().get(ImportRuntimeKeys.SUCCESS_COUNT)).isEqualTo(2L);
    assertThat(Files.exists(validated)).isFalse();
    verify(plugin, never()).loadChunk(any(), any());
  }

  @Test
  @DisplayName("首次装载完成后状态写入失败，位点恢复不重复加载或累加成功数")
  void completedAfterLoad_statusFailureDoesNotDoubleCount() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(
            ProcessingPosition.empty(), new ProcessingPosition(marker(validated, 2L), 2L, true));
    when(plugin.loadChunk(any(), any())).thenReturn(2);
    Mockito.doThrow(new IllegalStateException("status unavailable"))
        .doNothing()
        .when(runtimeRepository)
        .updateFileStatus(eq(99L), eq("LOADED"), any());

    assertThat(loadStep.execute(ctx).success()).isFalse();
    assertThat(Files.exists(validated)).isTrue();
    assertThat(loadStep.execute(ctx).success()).isTrue();
    assertThat(ctx.getAttributes().get(ImportRuntimeKeys.SUCCESS_COUNT)).isEqualTo(2L);
    verify(plugin, times(1)).loadChunk(any(), any());
  }

  @Test
  @DisplayName("空输入完成时位点仍绑定文件指纹")
  void shouldRecordFileIdentity_whenInputIsEmpty() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of());
    String expectedMarker = marker(validated, 0L);
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 0L);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(ProcessingPosition.empty());

    assertThat(loadStep.execute(ctx).success()).isTrue();
    verify(positionStore)
        .advance(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD, expectedMarker, 0L);
    verify(positionStore).markCompleted(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD);
    verify(plugin, never()).loadChunk(any(), any());
  }

  @Test
  @DisplayName("旧格式位点缺少内容指纹时不允许按行号续跑")
  void shouldRejectCheckpoint_whenFileIdentityIsMissing() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(new ProcessingPosition("1", 1L, false));

    assertThat(loadStep.execute(ctx).success()).isFalse();
    assertThat(Files.exists(validated)).isTrue();
    verify(plugin, never()).loadChunk(any(), any());
    verify(positionStore, never()).advance(any(), anyLong(), any(), any(), anyLong());
  }

  @Test
  @DisplayName("已完成位点对应的文件内容改变时不能直接报告成功")
  void shouldRejectCompletedCheckpoint_whenFileChanges() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    String originalMarker = marker(validated, 2L);
    Files.writeString(
        validated,
        objectMapper.writeValueAsString(row("C2")) + "\n"
            + objectMapper.writeValueAsString(row("C1")) + "\n");
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(new ProcessingPosition(originalMarker, 2L, true));

    assertThat(loadStep.execute(ctx).success()).isFalse();
    assertThat(Files.exists(validated)).isTrue();
    verify(runtimeRepository, never()).updateFileStatus(any(), any(), any());
    verify(plugin, never()).loadChunk(any(), any());
  }

  @Test
  @DisplayName("已完成位点的累计数与校验数不一致时不报告成功")
  void shouldRejectCompletedCheckpoint_whenValidationCountDiffers() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 2L);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(new ProcessingPosition(marker(validated, 1L), 1L, true));

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(Files.exists(validated)).isTrue();
    verify(runtimeRepository, never()).updateFileStatus(any(), any(), any());
    verify(plugin, never()).loadChunk(any(), any());
  }

  @Test
  @DisplayName("暂存文件超出校验记录数时不写入超额分块")
  void shouldRejectExcessValidatedRows_beforeChunkWrite() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 1L);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(ProcessingPosition.empty());

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(Files.exists(validated)).isTrue();
    verify(plugin, never()).loadChunk(any(), any());
    verify(positionStore, never()).advance(any(), anyLong(), any(), any(), anyLong());
  }

  @Test
  @DisplayName("校验文件截短时不标记加载位点完成")
  void shouldNotCompleteCheckpoint_whenValidatedFileIsTruncated() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 2L);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(ProcessingPosition.empty());
    when(plugin.loadChunk(any(), any())).thenReturn(1);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(Files.exists(validated)).isTrue();
    verify(positionStore)
        .advance(
            eq(TENANT),
            eq(PIPELINE_INSTANCE_ID),
            eq(ProcessingStage.LOAD),
            ArgumentMatchers.argThat(value -> value.matches("1:[0-9a-f]{64}")),
            eq(1L));
    verify(positionStore, never()).markCompleted(any(), anyLong(), any());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1, 3})
  @DisplayName("插件未确认整批时失败且不推进位点")
  void shouldNotAdvanceCheckpoint_whenPluginAcknowledgementIsIncomplete(int acknowledged)
      throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(ProcessingPosition.empty());
    when(plugin.loadChunk(any(), any())).thenReturn(acknowledged);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(Files.exists(validated)).isTrue();
    verify(positionStore, never()).advance(any(), anyLong(), any(), any(), anyLong());
    verify(positionStore, never()).markCompleted(any(), anyLong(), any());
  }

  @Test
  @DisplayName("开关开 + 首次跑:每 chunk 后 advance,结束后 markCompleted")
  void checkpointEnabled_firstRun_advancesPerChunk() throws Exception {
    checkpointProps.setEnabled(true);
    // chunk_size=2 通过 template 传;3 行 → 2 chunks(2 + 1)
    Path validated = writeNdjson(List.of(row("C1"), row("C2"), row("C3")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    ctx.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("chunk_size", 2));
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(ProcessingPosition.empty());
    when(plugin.loadChunk(any(), any())).thenReturn(2).thenReturn(1);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    verify(plugin, times(2)).loadChunk(any(), any());

    ArgumentCaptor<String> markerCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Long> incCaptor = ArgumentCaptor.forClass(Long.class);
    verify(positionStore, times(2))
        .advance(
            eq(TENANT),
            eq(PIPELINE_INSTANCE_ID),
            eq(ProcessingStage.LOAD),
            markerCaptor.capture(),
            incCaptor.capture());
    assertThat(markerCaptor.getAllValues()).hasSize(2);
    assertThat(markerCaptor.getAllValues().get(0)).matches("2:[0-9a-f]{64}");
    assertThat(markerCaptor.getAllValues().get(1)).matches("3:[0-9a-f]{64}");
    assertThat(incCaptor.getAllValues()).containsExactly(2L, 1L);

    verify(positionStore).markCompleted(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD);
  }

  @Test
  @DisplayName("开关开 + 续跑(位点=2):skip 前 2 行,从第 3 行起 advance")
  void checkpointEnabled_resume_skipsAhead() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2"), row("C3"), row("C4")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    ctx.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("chunk_size", 5));
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(new ProcessingPosition(marker(validated, 2L), 2L, false));
    when(plugin.loadChunk(any(), any())).thenReturn(2);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    // 只跑剩下 2 行(C3 / C4),只一次 loadChunk
    verify(plugin, times(1)).loadChunk(any(), any());
    ArgumentCaptor<String> markerCaptor = ArgumentCaptor.forClass(String.class);
    verify(positionStore)
        .advance(
            eq(TENANT),
            eq(PIPELINE_INSTANCE_ID),
            eq(ProcessingStage.LOAD),
            markerCaptor.capture(),
            eq(2L));
    assertThat(markerCaptor.getValue()).matches("4:[0-9a-f]{64}"); // skip 2 + 2 lines read = line 4
    verify(positionStore).markCompleted(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD);
  }

  @Test
  @DisplayName("开关开 + partitionCount>1:多分区共享 pipelineInstanceId,位点会互撞 → 降级为不续跑")
  void checkpointEnabled_multiPartition_degradesToDisabled() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 2);
    when(plugin.loadChunk(any(), any())).thenReturn(2);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    // K 个 partition 共享同一 pipeline_instance(UPSERT on related_job_instance_id),
    // (tenant, instanceId, LOAD) 位点行会跨 partition 互写 → 必须整体降级,不读不写位点。
    verify(positionStore, never()).load(any(), anyLong(), any());
    verify(positionStore, never()).advance(any(), anyLong(), any(), any(), anyLong());
    verify(positionStore, never()).markCompleted(any(), anyLong(), any());
  }

  @Test
  @DisplayName("开关开 + pipelineInstanceId 缺失:退化为开关关行为(不读/写位点)")
  void checkpointEnabled_butMissingPipelineInstanceId_degradesToDisabled() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    // 不放 PIPELINE_INSTANCE_ID
    when(plugin.loadChunk(any(), any())).thenReturn(1);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    verify(positionStore, never()).load(any(), anyLong(), any());
    verify(positionStore, never()).advance(any(), anyLong(), any(), any(), anyLong());
    verify(positionStore, never()).markCompleted(any(), anyLong(), any());
  }

  // ─── helpers (与 LoadStepTest 同款) ─────────────────────────────────────────

  private static String marker(Path file, long line) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    return line + ":" + HexFormat.of().formatHex(digest);
  }

  private static long anyLong() {
    return ArgumentMatchers.anyLong();
  }

  private static Long toLong(Object v) {
    if (v == null) return null;
    if (v instanceof Number n) return n.longValue();
    if (v instanceof String s && !s.isBlank()) {
      try {
        return Long.parseLong(s);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  private ImportJobContext baseContext() {
    ImportJobContext ctx = new ImportJobContext();
    ctx.setTenantId(TENANT);
    ctx.setJobCode("JOB");
    ctx.setBizDate("2026-06-02");
    ctx.setWorkerId("w1");
    ctx.setFileId("99");
    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.FILE_ID, 99L);
    ctx.setAttributes(attrs);
    return ctx;
  }

  private ImportJobContext streamingContext(Path validated) {
    ImportJobContext ctx = baseContext();
    ctx.getAttributes().put(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH, validated.toString());
    return ctx;
  }

  private Path writeNdjson(List<Map<String, Object>> rows) throws Exception {
    Path file = Files.createTempFile("validated-", ".ndjson");
    tempPaths.add(file);
    StringBuilder sb = new StringBuilder();
    for (Map<String, Object> r : rows) {
      sb.append(objectMapper.writeValueAsString(r)).append('\n');
    }
    Files.writeString(file, sb.toString());
    return file;
  }

  private static Map<String, Object> row(String customerNo) {
    return Map.of("customer_no", customerNo, "name", "N-" + customerNo);
  }
}
