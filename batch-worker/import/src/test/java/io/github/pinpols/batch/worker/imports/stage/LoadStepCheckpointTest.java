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
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPosition;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingStage;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration.FileProcessing;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.plugin.ImportLoadPluginRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-038 P2 续跑位点行为单测 — 补 {@link LoadStepTest} 之外的开关开 / 开关关 / 续跑 / 已完成跳过 等关键分支。
 *
 * <p>共享原测的 fixture 风格(@TempDir / 不 transactional / mock plugin)。
 */
@ExtendWith(MockitoExtension.class)
class LoadStepCheckpointTest {

  private static final String TENANT = "tenant-A";
  private static final long PIPELINE_INSTANCE_ID = 7001L;

  @Mock private PlatformFileRuntimeRepository runtimeRepository;
  @Mock private ImportLoadPlugin plugin;
  @Mock private ProcessingPositionStore positionStore;

  private ImportLoadPluginRegistry registry;
  private ImportWorkerConfiguration workerConfig;
  private ObjectMapper objectMapper;
  private WorkerCheckpointProperties checkpointProps;
  private LoadStep loadStep;

  @TempDir Path tempDir;
  private final List<Path> tempPaths = new ArrayList<>();

  @BeforeEach
  void setUp() {
    when(plugin.id()).thenReturn(WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    // ADR-038 R3-3:续跑开关开时 LoadStep 会校验 plugin 幂等能力;mock 默认返回 null,需显式 stub。
    // disabled 路径不会触发(不调 idempotencyCapability),用 lenient 避免 UnnecessaryStubbing。
    org.mockito.Mockito.lenient()
        .when(plugin.idempotencyCapability())
        .thenReturn(IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT);
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
    checkpointProps = new WorkerCheckpointProperties();
    loadStep =
        new LoadStep(
            registry,
            runtimeRepository,
            workerConfig,
            objectMapper,
            checkpointProps,
            positionStore);
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
    when(runtimeRepository.toLong(any())).thenReturn(99L);
    when(plugin.loadChunk(any(), any())).thenReturn(1);

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
        .thenReturn(ProcessingPosition.completed(2L));
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLong(inv.getArgument(0)));

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    verify(plugin, never()).loadChunk(any(), any());
    verify(positionStore, never()).advance(any(), anyLong(), any(), any(), anyLong());
    verify(positionStore, never()).markCompleted(any(), anyLong(), any()); // 已 completed 不重复标记
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
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLong(inv.getArgument(0)));

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
    assertThat(markerCaptor.getAllValues()).containsExactly("2", "3");
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
        .thenReturn(new ProcessingPosition("2", 2L, false));
    when(plugin.loadChunk(any(), any())).thenReturn(2);
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLong(inv.getArgument(0)));

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
    assertThat(markerCaptor.getValue()).isEqualTo("4"); // skip 2 + 2 lines read = line 4
    verify(positionStore).markCompleted(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD);
  }

  @Test
  @DisplayName("开关开 + pipelineInstanceId 缺失:退化为开关关行为(不读/写位点)")
  void checkpointEnabled_butMissingPipelineInstanceId_degradesToDisabled() throws Exception {
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    // 不放 PIPELINE_INSTANCE_ID
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLong(inv.getArgument(0)));
    when(plugin.loadChunk(any(), any())).thenReturn(1);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    verify(positionStore, never()).load(any(), anyLong(), any());
    verify(positionStore, never()).advance(any(), anyLong(), any(), any(), anyLong());
    verify(positionStore, never()).markCompleted(any(), anyLong(), any());
  }

  // ─── helpers (与 LoadStepTest 同款) ─────────────────────────────────────────

  private static long anyLong() {
    return org.mockito.ArgumentMatchers.anyLong();
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
