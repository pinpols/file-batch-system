package com.example.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.plugin.IdempotencyCapability;
import com.example.batch.common.plugin.ImportLoadPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.worker.core.config.WorkerCheckpointProperties;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingPosition;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingStage;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration.FileProcessing;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.plugin.GenericJdbcMappedImportLoadPlugin;
import com.example.batch.worker.imports.plugin.ImportLoadPluginRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-038 R3-3 前置校验单测 — 续跑开关开时 plugin 必须自报幂等能力,否则 LoadStep 转 {@code IMPORT_LOAD_CONFIG_INVALID}
 * 失败拒跑。
 *
 * <p>4 个 case:enabled + IDEMPOTENT pass / enabled + NONE throw / enabled + UNKNOWN throw / disabled
 * 任何 cap 都 pass。
 */
@ExtendWith(MockitoExtension.class)
class LoadStepCheckpointPrecheckTest {

  private static final String TENANT = "tenant-A";
  private static final long PIPELINE_INSTANCE_ID = 8002L;

  @Mock private PlatformFileRuntimeRepository runtimeRepository;
  @Mock private ImportLoadPlugin plugin;
  @Mock private GenericJdbcMappedImportLoadPlugin jdbcMappedPlugin;
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
  @DisplayName("enabled=true + IDEMPOTENT_BY_UNIQUE_CONSTRAINT:正常跑")
  void enabled_idempotentPlugin_passes() throws Exception {
    checkpointProps.setEnabled(true);
    when(plugin.idempotencyCapability())
        .thenReturn(IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT);
    when(plugin.loadChunk(any(), any())).thenReturn(1);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(ProcessingPosition.empty());
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLong(inv.getArgument(0)));

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    verify(plugin).loadChunk(any(), any());
  }

  @Test
  @DisplayName("enabled=true + NONE:拒跑,返回 IMPORT_LOAD_CONFIG_INVALID,不调 plugin.loadChunk")
  void enabled_nonePlugin_rejects() throws Exception {
    checkpointProps.setEnabled(true);
    when(plugin.idempotencyCapability()).thenReturn(IdempotencyCapability.NONE);
    lenient().when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLong(inv.getArgument(0)));

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_LOAD_CONFIG_INVALID");
    verify(plugin, never()).loadChunk(any(), any());
    verify(positionStore, never()).load(any(), org.mockito.ArgumentMatchers.anyLong(), any());
  }

  @Test
  @DisplayName("enabled=true + UNKNOWN(默认未 override):拒跑")
  void enabled_unknownPlugin_rejects() throws Exception {
    checkpointProps.setEnabled(true);
    when(plugin.idempotencyCapability()).thenReturn(IdempotencyCapability.UNKNOWN);
    lenient().when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLong(inv.getArgument(0)));

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_LOAD_CONFIG_INVALID");
    verify(plugin, never()).loadChunk(any(), any());
  }

  @Test
  @DisplayName("enabled=true + PARTITION_REPLACE_COPY:拒跑,不清分区不进位点")
  void enabled_partitionReplaceCopy_rejectsCheckpointResume() throws Exception {
    checkpointProps.setEnabled(true);
    when(jdbcMappedPlugin.id()).thenReturn(WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    when(jdbcMappedPlugin.isPartitionReplaceCopy(any())).thenReturn(true);
    registry = new ImportLoadPluginRegistry(List.of(jdbcMappedPlugin));
    loadStep =
        new LoadStep(
            registry,
            runtimeRepository,
            workerConfig,
            objectMapper,
            checkpointProps,
            positionStore);

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_LOAD_CONFIG_INVALID");
    verify(jdbcMappedPlugin, never()).preparePartitionReplace(any());
    verify(jdbcMappedPlugin, never()).loadChunk(any(), any());
    verify(positionStore, never()).load(any(), org.mockito.ArgumentMatchers.anyLong(), any());
  }

  @Test
  @DisplayName("enabled=false:不校验,UNKNOWN/NONE plugin 也能跑(不进续跑路径)")
  void disabled_anyCapability_passes() throws Exception {
    checkpointProps.setEnabled(false);
    // 即使 plugin 报 NONE,关闭开关时也跑;default UNKNOWN 同理(不 stub)
    when(plugin.loadChunk(any(), any())).thenReturn(1);
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLong(inv.getArgument(0)));

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    verify(plugin).loadChunk(any(), any());
    // 不进位点路径:positionStore 不被调
    verify(positionStore, never()).load(any(), org.mockito.ArgumentMatchers.anyLong(), any());
  }

  // ─── helpers ───────────────────────────────────────────────────────────────

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
