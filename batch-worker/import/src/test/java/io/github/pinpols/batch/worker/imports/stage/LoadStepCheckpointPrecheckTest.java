package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
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
import io.github.pinpols.batch.worker.imports.plugin.GenericJdbcMappedImportLoadPlugin;
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
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-038 R3-3 前置校验单测 — 续跑开关开时 plugin 必须自报幂等能力,否则 LoadStep 转 {@code IMPORT_LOAD_CONFIG_INVALID}
 * 失败拒跑。
 *
 * <p>覆盖 checkpoint 前置校验及关闭/多分区路径的非幂等插件执行围栏。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("导入续跑前置校验单测:插件幂等能力自报与开关关闭时的放行语义")
class LoadStepCheckpointPrecheckTest {

  private static final String TENANT = "tenant-A";
  private static final long PIPELINE_INSTANCE_ID = 8002L;

  @Mock
  private PlatformFileRecordRepository runtimeRepository;

  @Mock
  private ImportLoadPlugin plugin;

  @Mock
  private GenericJdbcMappedImportLoadPlugin jdbcMappedPlugin;

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
  @DisplayName("enabled=true + IDEMPOTENT_BY_UNIQUE_CONSTRAINT:正常跑")
  void enabled_idempotentPlugin_passes() throws Exception {
    checkpointProps.setEnabled(true);
    when(plugin.idempotencyCapability())
        .thenReturn(IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT);
    when(plugin.loadChunk(any(), any())).thenReturn(1);
    when(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD))
        .thenReturn(ProcessingPosition.empty());

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

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_LOAD_CONFIG_INVALID");
    verify(plugin, never()).loadChunk(any(), any());
    verify(positionStore, never()).load(any(), ArgumentMatchers.anyLong(), any());
  }

  @Test
  @DisplayName("enabled=true + UNKNOWN(默认未 override):拒跑")
  void enabled_unknownPlugin_rejects() throws Exception {
    checkpointProps.setEnabled(true);
    when(plugin.idempotencyCapability()).thenReturn(IdempotencyCapability.UNKNOWN);

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
    loadStep = new LoadStep(
        registry,
        runtimeRepository,
        workerConfig,
        objectMapper,
        checkpointProps,
        positionStore,
        new PipelineStageProgressRegistry(),
        taskExecutionClient);

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_LOAD_CONFIG_INVALID");
    verify(jdbcMappedPlugin, never()).preparePartitionReplace(any());
    verify(jdbcMappedPlugin, never()).loadChunk(any(), any());
    verify(positionStore, never()).load(any(), ArgumentMatchers.anyLong(), any());
  }

  @Test
  @DisplayName("PARTITION_REPLACE_COPY + partitionCount>1:拒跑,避免多个分片互相清同一目标分区")
  void partitionReplaceCopy_multipleWorkerPartitions_rejects() throws Exception {
    checkpointProps.setEnabled(false);
    when(jdbcMappedPlugin.id()).thenReturn(WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    when(jdbcMappedPlugin.isPartitionReplaceCopy(any())).thenReturn(true);
    registry = new ImportLoadPluginRegistry(List.of(jdbcMappedPlugin));
    loadStep = new LoadStep(
        registry,
        runtimeRepository,
        workerConfig,
        objectMapper,
        checkpointProps,
        positionStore,
        new PipelineStageProgressRegistry(),
        taskExecutionClient);

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 2);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_LOAD_CONFIG_INVALID");
    verify(jdbcMappedPlugin, never()).preparePartitionReplace(any());
    verify(jdbcMappedPlugin, never()).loadChunk(any(), any());
  }

  @Test
  @DisplayName("分区替换即使插件声明唯一约束幂等也必须先持久围栏")
  void partitionReplaceCopy_uniqueConstraintCapability_persistsFenceBeforeTargetMutation()
      throws Exception {
    checkpointProps.setEnabled(false);
    when(jdbcMappedPlugin.id()).thenReturn(WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    when(jdbcMappedPlugin.idempotencyCapability())
        .thenReturn(IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT);
    when(jdbcMappedPlugin.isPartitionReplaceCopy(any())).thenReturn(true);
    when(jdbcMappedPlugin.loadChunk(any(), any())).thenReturn(1);
    when(taskExecutionClient.markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1"))
        .thenReturn(true);
    registry = new ImportLoadPluginRegistry(List.of(jdbcMappedPlugin));
    loadStep = new LoadStep(
        registry,
        runtimeRepository,
        workerConfig,
        objectMapper,
        checkpointProps,
        positionStore,
        new PipelineStageProgressRegistry(),
        taskExecutionClient);

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext context = streamingContext(validated);
    context.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 41L);
    context.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "inv-1");

    ImportStageResult result = loadStep.execute(context);

    assertThat(result.success()).isTrue();
    InOrder executionOrder = inOrder(taskExecutionClient, jdbcMappedPlugin);
    executionOrder
        .verify(taskExecutionClient)
        .markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1");
    executionOrder.verify(jdbcMappedPlugin).preparePartitionReplace(any());
    executionOrder.verify(jdbcMappedPlugin).loadChunk(any(), any());
  }

  @Test
  @DisplayName("非幂等分区替换在准备目标分区前先持久化执行围栏")
  void nonReplaySafePartitionReplace_persistsFenceBeforePreparingTarget() throws Exception {
    checkpointProps.setEnabled(false);
    when(jdbcMappedPlugin.id()).thenReturn(WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    when(jdbcMappedPlugin.idempotencyCapability()).thenReturn(IdempotencyCapability.UNKNOWN);
    when(jdbcMappedPlugin.isPartitionReplaceCopy(any())).thenReturn(true);
    when(jdbcMappedPlugin.loadChunk(any(), any())).thenReturn(1);
    when(taskExecutionClient.markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1"))
        .thenReturn(true);
    registry = new ImportLoadPluginRegistry(List.of(jdbcMappedPlugin));
    loadStep = new LoadStep(
        registry,
        runtimeRepository,
        workerConfig,
        objectMapper,
        checkpointProps,
        positionStore,
        new PipelineStageProgressRegistry(),
        taskExecutionClient);

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 41L);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "inv-1");

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    InOrder executionOrder = inOrder(taskExecutionClient, jdbcMappedPlugin);
    executionOrder
        .verify(taskExecutionClient)
        .markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1");
    executionOrder.verify(jdbcMappedPlugin).preparePartitionReplace(any());
    executionOrder.verify(jdbcMappedPlugin).loadChunk(any(), any());
  }

  @Test
  @DisplayName("分区替换围栏未确认时即使插件声明唯一约束幂等也不清理目标分区")
  void uniqueConstraintPartitionReplace_rejectsWhenExecutionFenceCannotBeConfirmed()
      throws Exception {
    checkpointProps.setEnabled(false);
    when(jdbcMappedPlugin.id()).thenReturn(WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    when(jdbcMappedPlugin.idempotencyCapability())
        .thenReturn(IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT);
    when(jdbcMappedPlugin.isPartitionReplaceCopy(any())).thenReturn(true);
    when(taskExecutionClient.markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1"))
        .thenReturn(false);
    registry = new ImportLoadPluginRegistry(List.of(jdbcMappedPlugin));
    loadStep = new LoadStep(
        registry,
        runtimeRepository,
        workerConfig,
        objectMapper,
        checkpointProps,
        positionStore,
        new PipelineStageProgressRegistry(),
        taskExecutionClient);

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 41L);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "inv-1");

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    verify(jdbcMappedPlugin, never()).preparePartitionReplace(any());
    verify(jdbcMappedPlugin, never()).loadChunk(any(), any());
  }

  @Test
  @DisplayName("关闭续跑时先持久化非幂等围栏,再分块调用未知能力插件")
  void disabled_unknownPlugin_marksExecutionBeforeFirstChunk() throws Exception {
    checkpointProps.setEnabled(false);
    when(plugin.idempotencyCapability()).thenReturn(IdempotencyCapability.UNKNOWN);
    when(plugin.loadChunk(any(), any())).thenReturn(2, 1);
    when(taskExecutionClient.markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1"))
        .thenReturn(true);

    Path validated = writeNdjson(List.of(row("C1"), row("C2"), row("C3")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 41L);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "inv-1");

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    InOrder executionOrder = inOrder(taskExecutionClient, plugin);
    executionOrder
        .verify(taskExecutionClient)
        .markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1");
    executionOrder.verify(plugin, times(2)).loadChunk(any(), any());
    verify(taskExecutionClient).markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1");
    // 不进位点路径:positionStore 不被调
    verify(positionStore, never()).load(any(), ArgumentMatchers.anyLong(), any());
  }

  @Test
  @DisplayName("未知插件部分确认时先建立围栏，再失败并保留暂存文件")
  void disabled_unknownPlugin_partialAcknowledgementPreservesFence() throws Exception {
    checkpointProps.setEnabled(false);
    when(plugin.idempotencyCapability()).thenReturn(IdempotencyCapability.UNKNOWN);
    when(plugin.loadChunk(any(), any())).thenReturn(1);
    when(taskExecutionClient.markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1"))
        .thenReturn(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 41L);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "inv-1");

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(Files.exists(validated)).isTrue();
    InOrder executionOrder = inOrder(taskExecutionClient, plugin);
    executionOrder
        .verify(taskExecutionClient)
        .markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1");
    executionOrder.verify(plugin).loadChunk(any(), any());
    verify(positionStore, never()).load(any(), ArgumentMatchers.anyLong(), any());
  }

  @Test
  @DisplayName("多分区降级关闭位点续跑时仍先设置未知插件执行围栏")
  void multiPartition_unknownPlugin_marksExecutionBeforeLoad() throws Exception {
    checkpointProps.setEnabled(true);
    when(plugin.idempotencyCapability()).thenReturn(IdempotencyCapability.UNKNOWN);
    when(plugin.loadChunk(any(), any())).thenReturn(1);
    when(taskExecutionClient.markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1"))
        .thenReturn(true);

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 41L);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "inv-1");
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 2);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isTrue();
    InOrder executionOrder = inOrder(taskExecutionClient, plugin);
    executionOrder
        .verify(taskExecutionClient)
        .markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1");
    executionOrder.verify(plugin).loadChunk(any(), any());
    verify(positionStore, never()).load(any(), ArgumentMatchers.anyLong(), any());
  }

  @Test
  @DisplayName("非幂等执行围栏未获确认时以 UNKNOWN 失败且不调用加载插件")
  void disabled_unknownPlugin_rejectsWhenExecutionFenceCannotBeConfirmed() throws Exception {
    checkpointProps.setEnabled(false);
    when(plugin.idempotencyCapability()).thenReturn(IdempotencyCapability.NONE);
    when(taskExecutionClient.markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1"))
        .thenReturn(false);

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 41L);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "inv-1");

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    verify(taskExecutionClient).markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1");
    verify(plugin, never()).loadChunk(any(), any());
  }

  @Test
  @DisplayName("租约身份缺失时拒绝执行非幂等插件")
  void disabled_unknownPlugin_rejectsWhenExecutionIdentityIsMissing() throws Exception {
    checkpointProps.setEnabled(false);
    when(plugin.idempotencyCapability()).thenReturn(IdempotencyCapability.UNKNOWN);

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    verify(taskExecutionClient, never())
        .markNonIdempotentExecutionStarted(any(), any(), any(), any());
    verify(plugin, never()).loadChunk(any(), any());
  }

  @Test
  @DisplayName("持久执行围栏服务异常时拒绝执行非幂等插件")
  void disabled_unknownPlugin_rejectsWhenExecutionFenceServiceFails() throws Exception {
    checkpointProps.setEnabled(false);
    when(plugin.idempotencyCapability()).thenReturn(IdempotencyCapability.UNKNOWN);
    when(taskExecutionClient.markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1"))
        .thenThrow(new IllegalStateException("orchestrator unavailable"));

    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext ctx = streamingContext(validated);
    ctx.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 41L);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "inv-1");

    ImportStageResult result = loadStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    verify(taskExecutionClient).markNonIdempotentExecutionStarted(TENANT, 41L, "w1", "inv-1");
    verify(plugin, never()).loadChunk(any(), any());
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
