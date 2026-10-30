package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ImportLoadContext;
import io.github.pinpols.batch.common.plugin.ImportLoadPlugin;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineStageProgressRegistry;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.DefaultProcessingPositionStore;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPosition;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingStage;
import io.github.pinpols.batch.worker.core.mapper.PipelineProgressMapper;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration.FileProcessing;
import io.github.pinpols.batch.worker.imports.config.JdbcMappedImportSecurityProperties;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.plugin.GenericJdbcMappedImportLoadPlugin;
import io.github.pinpols.batch.worker.imports.plugin.ImportLoadPluginRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR-038 P0 幂等前置校验 + 跨库补偿窗口加固 IT。
 *
 * <p>用**真实业务库(Testcontainers PG)** 跑 {@link GenericJdbcMappedImportLoadPlugin} 的 {@code ON
 * CONFLICT} 写入,配合一个进程内 {@link ProcessingPositionStore}(模拟落在**另一个库**的平台位点,刻意与业务库解耦 = 复现"跨库无
 * 1PC")。验证两条 P0 契约:
 *
 * <ol>
 *   <li><b>崩溃-重派补偿</b>:业务 chunk 已 commit 但位点 advance 前进程崩溃 → 重派从旧位点续跑、重做该 chunk, 靠 plugin 的 ON
 *       CONFLICT 幂等**不双写**。
 *   <li><b>非幂等 plugin 拒跑</b>:续跑开关开 + plugin 自报 {@code NONE} → LoadStep 在**任何业务写之前**转 {@code
 *       IMPORT_LOAD_CONFIG_INVALID} 拒跑,业务表零行。
 * </ol>
 *
 * <p>位点落进程内 store 而非平台 PG 是有意的:P0 要证的是"业务库不双写",不是位点持久化(已由 {@code
 * DefaultProcessingPositionStoreTest} 覆盖)。用 in-memory 位点反而更贴近"跨库两套资源"的现实。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("导入位点续跑崩溃重派集成测试:跨库位点下不双写与非幂等插件拒跑")
class LoadStepCheckpointCrashResumeIntegrationTest {

  private static final String TENANT = "t1";
  private static final long PIPELINE_INSTANCE_ID = 9101L;
  private static final String BIZ_DATE = "2026-06-02";

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.business();

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private GenericJdbcMappedImportLoadPlugin plugin;
  private ObjectMapper objectMapper;
  private PlatformFileRecordRepository runtimeRepository;
  private ImportWorkerConfiguration workerConfig;
  private final List<Path> tempPaths = new ArrayList<>();

  @BeforeEach
  void setUp() {
    RlsTenantContextHolder.set(TENANT);
    dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl() + "&stringtype=unspecified");
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    objectMapper = new ObjectMapper();

    JdbcMappedImportSecurityProperties security = new JdbcMappedImportSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    plugin = new GenericJdbcMappedImportLoadPlugin(dataSource, objectMapper, security);

    final String dropSql = """
        DROP SCHEMA IF EXISTS biz CASCADE
    """;
    jdbcTemplate.execute(dropSql);
    final String createSql = """
        CREATE SCHEMA biz
    """;
    jdbcTemplate.execute(createSql);
    final String createBizCkptCustomerSql = """
        CREATE TABLE biz.ckpt_customer (
          tenant_id text NOT NULL,
          customer_no text NOT NULL,
          customer_name text,
          PRIMARY KEY (tenant_id, customer_no)
        )
        """;
    jdbcTemplate.execute(createBizCkptCustomerSql);

    runtimeRepository = mock(PlatformFileRecordRepository.class);
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
  }

  @AfterEach
  void cleanup() throws Exception {
    RlsTenantContextHolder.clear();
    for (Path p : tempPaths) {
      Files.deleteIfExists(p);
    }
  }

  @Test
  @DisplayName("崩溃窗口(业务已 commit、位点未 advance)重派:重做 chunk 靠 ON CONFLICT 不双写")
  void crashBetweenBusinessCommitAndAdvance_resumeDoesNotDoubleWrite() throws Exception {
    WorkerCheckpointProperties checkpointProps = new WorkerCheckpointProperties();
    checkpointProps.setEnabled(true);
    CrashInjectingPositionStore positionStore = new CrashInjectingPositionStore();
    LoadStep loadStep = newLoadStep(plugin, checkpointProps, positionStore);

    // 4 行 → chunk_size=2 → 两个 chunk(C1,C2 / C3,C4)。
    Path validated = writeNdjson(List.of(row("C1"), row("C2"), row("C3"), row("C4")));

    // ── Attempt 1:第一个 chunk 的业务写 commit 后,位点 advance 时崩溃 ──
    positionStore.failNextAdvance();
    ImportStageResult attempt1 = loadStep.execute(newContext(validated));
    assertThat(attempt1.success()).isFalse(); // advance 抛错冒泡 → 阶段失败(= 进程崩溃)
    // 业务库已落第一个 chunk(plugin 自 auto-commit);位点因 advance 崩溃而未持久
    assertThat(customerNos()).containsExactly("C1", "C2");
    assertThat(positionStore
            .load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD)
            .positionMarker())
        .isNull();

    // ── Attempt 2:重派,同 pipelineInstanceId,位点仍为空 → 从第 0 行续跑,重做 C1,C2 ──
    ImportStageResult attempt2 = loadStep.execute(newContext(validated));
    assertThat(attempt2.success()).isTrue();
    // 关键断言:C1,C2 被重做,但 ON CONFLICT 幂等 → 各只有一行,不双写;C3,C4 补齐。
    assertThat(customerNos()).containsExactly("C1", "C2", "C3", "C4");
    assertThat(rowCount()).isEqualTo(4);
    // 位点在完整重跑后标记完成
    assertThat(positionStore
            .load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD)
            .completed())
        .isTrue();
  }

  @Test
  @DisplayName("非幂等 plugin(NONE)+ 续跑开关开:拒跑 IMPORT_LOAD_CONFIG_INVALID,业务表零行")
  void nonIdempotentPlugin_withCheckpointEnabled_rejectedBeforeAnyBusinessWrite() throws Exception {
    WorkerCheckpointProperties checkpointProps = new WorkerCheckpointProperties();
    checkpointProps.setEnabled(true);
    CrashInjectingPositionStore positionStore = new CrashInjectingPositionStore();
    // 若前置校验失效,该 plugin 的 loadChunk 会真的写库 → 断言表非空即可暴露
    ImportLoadPlugin nonIdempotent = new WritingNonIdempotentPlugin(jdbcTemplate);
    LoadStep loadStep = newLoadStep(nonIdempotent, checkpointProps, positionStore);

    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    ImportStageResult result = loadStep.execute(newContext(validated));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_LOAD_CONFIG_INVALID");
    assertThat(rowCount()).isZero(); // 拒跑发生在任何业务写之前
  }

  @Test
  @DisplayName("续跑时校验文件同数量但顺序变化，不能跳过未写入记录")
  void shouldRejectOldCheckpoint_whenValidatedFileIsReordered() throws Exception {
    WorkerCheckpointProperties checkpointProps = new WorkerCheckpointProperties();
    checkpointProps.setEnabled(true);
    CrashInjectingPositionStore positionStore = new CrashInjectingPositionStore();
    ImportLoadPlugin failSecondChunk = new ImportLoadPlugin() {
      private int calls;

      @Override
      public String id() {
        return plugin.id();
      }

      @Override
      public IdempotencyCapability idempotencyCapability() {
        return IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT;
      }

      @Override
      public int loadChunk(ImportLoadContext context, List<Map<String, Object>> records)
          throws Exception {
        if (++calls == 2) {
          throw new IllegalStateException("injected second chunk failure");
        }
        return plugin.loadChunk(context, records);
      }
    };
    LoadStep loadStep = newLoadStep(failSecondChunk, checkpointProps, positionStore);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    Map<String, Object> template = new LinkedHashMap<>(templateConfig());
    template.put("chunk_size", 1);
    ImportJobContext first = newContext(validated);
    first.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, template);
    first.getAttributes().put(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 2L);

    assertThat(loadStep.execute(first).success()).isFalse();
    assertThat(customerNos()).containsExactly("C1");
    assertThat(positionStore
            .load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD)
            .positionMarker())
        .startsWith("1:");
    Files.writeString(
        validated,
        objectMapper.writeValueAsString(row("C2")) + "\n"
            + objectMapper.writeValueAsString(row("C1")) + "\n");
    ImportJobContext resumed = newContext(validated);
    resumed.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, template);
    resumed.getAttributes().put(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 2L);

    ImportStageResult retry = loadStep.execute(resumed);
    assertThat(customerNos()).containsExactly("C1");
    assertThat(retry.success()).isFalse();
    assertThat(Files.exists(validated)).isTrue();
  }

  @Test
  @DisplayName("校验文件截短后重建文件须清理旧位点，再重跑补齐业务记录")
  void shouldRequireCheckpointReset_whenValidatedFileIsReconstructed() throws Exception {
    WorkerCheckpointProperties checkpointProps = new WorkerCheckpointProperties();
    checkpointProps.setEnabled(true);
    CrashInjectingPositionStore positionStore = new CrashInjectingPositionStore();
    LoadStep loadStep = newLoadStep(plugin, checkpointProps, positionStore);
    Path validated = writeNdjson(List.of(row("C1")));
    ImportJobContext first = newContext(validated);
    first.getAttributes().put(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 2L);

    assertThat(loadStep.execute(first).success()).isFalse();
    assertThat(customerNos()).containsExactly("C1");
    ProcessingPosition position =
        positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD);
    assertThat(position.completed()).isFalse();
    assertThat(position.positionMarker()).startsWith("1:");
    assertThat(Files.exists(validated)).isTrue();

    Files.writeString(
        validated,
        objectMapper.writeValueAsString(row("C1")) + "\n"
            + objectMapper.writeValueAsString(row("C2")) + "\n");
    ImportJobContext resumed = newContext(validated);
    resumed.getAttributes().put(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 2L);
    assertThat(loadStep.execute(resumed).success()).isFalse();
    assertThat(customerNos()).containsExactly("C1");
    positionStore.deleteAllStages(TENANT, PIPELINE_INSTANCE_ID);
    ImportJobContext restarted = newContext(validated);
    restarted.getAttributes().put(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT, 2L);
    assertThat(loadStep.execute(restarted).success()).isTrue();
    assertThat(customerNos()).containsExactly("C1", "C2");
    assertThat(positionStore
            .load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD)
            .completed())
        .isTrue();
  }

  @Test
  @DisplayName("部分写入不能推进位点，幂等重试补齐业务记录")
  void shouldRetainUnwrittenRows_whenPluginWritesPartially() throws Exception {
    WorkerCheckpointProperties checkpointProps = new WorkerCheckpointProperties();
    checkpointProps.setEnabled(true);
    CrashInjectingPositionStore positionStore = new CrashInjectingPositionStore();
    ImportLoadPlugin partialThenComplete = new ImportLoadPlugin() {
      private boolean partial = true;

      @Override
      public String id() {
        return plugin.id();
      }

      @Override
      public IdempotencyCapability idempotencyCapability() {
        return IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT;
      }

      @Override
      public int loadChunk(ImportLoadContext context, List<Map<String, Object>> records)
          throws Exception {
        if (partial) {
          partial = false;
          return plugin.loadChunk(context, records.subList(0, 1));
        }
        return plugin.loadChunk(context, records);
      }
    };
    LoadStep loadStep = newLoadStep(partialThenComplete, checkpointProps, positionStore);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));

    ImportStageResult first = loadStep.execute(newContext(validated));
    assertThat(customerNos()).containsExactly("C1");
    assertThat(first.success()).isFalse();
    assertThat(positionStore
            .load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD)
            .positionMarker())
        .isNull();
    assertThat(Files.exists(validated)).isTrue();

    assertThat(loadStep.execute(newContext(validated)).success()).isTrue();
    assertThat(customerNos()).containsExactly("C1", "C2");
  }

  @Test
  @DisplayName("真实业务库写入完成后状态更新失败，新上下文恢复位点且不重复加载")
  void completedCheckpoint_recoversAfterFileStatusFailure() throws Exception {
    WorkerCheckpointProperties checkpointProps = new WorkerCheckpointProperties();
    checkpointProps.setEnabled(true);
    CrashInjectingPositionStore positionStore = new CrashInjectingPositionStore();
    LoadStep loadStep = newLoadStep(plugin, checkpointProps, positionStore);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    Mockito.doThrow(new IllegalStateException("status unavailable"))
        .doNothing()
        .when(runtimeRepository)
        .updateFileStatus(eq(99L), eq("LOADED"), any());

    assertThat(loadStep.execute(newContext(validated)).success()).isFalse();
    assertThat(customerNos()).containsExactly("C1", "C2");
    assertThat(positionStore
            .load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD)
            .completed())
        .isTrue();
    assertThat(Files.exists(validated)).isTrue();

    ImportJobContext recovered = newContext(validated);
    assertThat(loadStep.execute(recovered).success()).isTrue();
    assertThat(rowCount()).isEqualTo(2);
    assertThat(recovered.getAttributes().get(ImportRuntimeKeys.SUCCESS_COUNT)).isEqualTo(2L);
    assertThat(Files.exists(validated)).isFalse();
  }

  @Test
  @DisplayName("真实位点与业务写入后状态失败，可按已完成位点恢复")
  void completedCheckpoint_recoversFromPostgresPosition() throws Exception {
    final String createSchemaSql = "create schema if not exists batch";
    final String createInstanceSql = """
        create table batch.pipeline_instance (id bigint primary key, tenant_id varchar(64) not null)
        """;
    final String createProgressSql = """
        create table batch.pipeline_progress (
          id bigserial primary key, tenant_id varchar(64) not null,
          pipeline_instance_id bigint not null, stage varchar(32) not null,
          position_marker varchar(512), processed_count bigint not null default 0,
          completed boolean not null default false, completed_at timestamptz,
          created_at timestamptz not null default current_timestamp,
          updated_at timestamptz not null default current_timestamp,
          unique (tenant_id, pipeline_instance_id, stage))
        """;
    final String insertInstanceSql =
        "insert into batch.pipeline_instance (id, tenant_id) values (?, ?)";
    jdbcTemplate.execute(createSchemaSql);
    jdbcTemplate.execute(createInstanceSql);
    jdbcTemplate.execute(createProgressSql);
    jdbcTemplate.update(insertInstanceSql, PIPELINE_INSTANCE_ID, TENANT);
    Configuration configuration = new Configuration(
        new Environment("checkpoint-it", new JdbcTransactionFactory(), dataSource));
    String resource = "mapper/PipelineProgressMapper.xml";
    try (InputStream input = Resources.getResourceAsStream(resource)) {
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
    }
    SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
    WorkerCheckpointProperties checkpointProps = new WorkerCheckpointProperties();
    checkpointProps.setEnabled(true);
    Path validated = writeNdjson(List.of(row("C1"), row("C2")));
    Mockito.doThrow(new IllegalStateException("status unavailable"))
        .doNothing()
        .when(runtimeRepository)
        .updateFileStatus(eq(99L), eq("LOADED"), any());
    try (SqlSession session = factory.openSession(true)) {
      ProcessingPositionStore positionStore = new DefaultProcessingPositionStore(
          session.getMapper(PipelineProgressMapper.class),
          new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
      LoadStep loadStep = newLoadStep(plugin, checkpointProps, positionStore);
      assertThat(loadStep.execute(newContext(validated)).success()).isFalse();
    }
    assertThat(Files.exists(validated)).isTrue();
    try (SqlSession session = factory.openSession(true)) {
      ProcessingPositionStore positionStore = new DefaultProcessingPositionStore(
          session.getMapper(PipelineProgressMapper.class),
          new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
      ProcessingPosition completed =
          positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD);
      assertThat(completed.completed()).isTrue();
      assertThat(completed.processedCount()).isEqualTo(2L);
      String originalContent = Files.readString(validated);
      Files.writeString(
          validated,
          objectMapper.writeValueAsString(row("C2")) + "\n"
              + objectMapper.writeValueAsString(row("C1")) + "\n");
      assertThat(newLoadStep(plugin, checkpointProps, positionStore)
              .execute(newContext(validated))
              .success())
          .isFalse();
      assertThat(Files.exists(validated)).isTrue();
      Files.writeString(validated, originalContent);
      ImportJobContext recovered = newContext(validated);
      assertThat(newLoadStep(plugin, checkpointProps, positionStore)
              .execute(recovered)
              .success())
          .isTrue();
      assertThat(customerNos()).containsExactly("C1", "C2");
      assertThat(recovered.getAttributes().get(ImportRuntimeKeys.SUCCESS_COUNT)).isEqualTo(2L);
      assertThat(Files.exists(validated)).isFalse();
    }
  }

  // ─── helpers ────────────────────────────────────────────────────────────────

  private LoadStep newLoadStep(
      ImportLoadPlugin loadPlugin,
      WorkerCheckpointProperties checkpointProps,
      ProcessingPositionStore positionStore) {
    ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(loadPlugin));
    return new LoadStep(
        registry,
        runtimeRepository,
        workerConfig,
        objectMapper,
        checkpointProps,
        positionStore,
        new PipelineStageProgressRegistry(),
        mock(TaskExecutionClient.class));
  }

  private ImportJobContext newContext(Path validated) {
    ImportJobContext ctx = new ImportJobContext();
    ctx.setTenantId(TENANT);
    ctx.setJobCode("IMPORT_CUSTOMER");
    ctx.setBizDate(BIZ_DATE);
    ctx.setWorkerId("w1");
    ctx.setFileId("99");
    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.FILE_ID, 99L);
    attrs.put(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH, validated.toString());
    attrs.put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, PIPELINE_INSTANCE_ID);
    attrs.put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig());
    ctx.setAttributes(attrs);
    return ctx;
  }

  private static Map<String, Object> templateConfig() {
    Map<String, Object> tc = new LinkedHashMap<>();
    tc.put("chunk_size", 2);
    tc.put(
        "jdbc_mapped_import",
        Map.of(
            "schema",
            "biz",
            "table",
            "ckpt_customer",
            "tenantColumn",
            "tenant_id",
            "columnMappings",
            List.of(
                Map.of("from", "customer_no", "to", "customer_no"),
                Map.of("from", "name", "to", "customer_name")),
            "conflictColumns",
            List.of("tenant_id", "customer_no"),
            "loadStrategy",
            "BATCH_UPSERT"));
    return tc;
  }

  private List<String> customerNos() {
    final String selectBizCkptCustomerSql = """
        SELECT customer_no FROM biz.ckpt_customer ORDER BY customer_no
    """;
    return jdbcTemplate.queryForList(selectBizCkptCustomerSql, String.class);
  }

  private int rowCount() {
    final String selectBizCkptCustomerSqlValue1 = """
        SELECT count(*) FROM biz.ckpt_customer
    """;
    Integer n = jdbcTemplate.queryForObject(selectBizCkptCustomerSqlValue1, Integer.class);
    return n == null ? 0 : n;
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

  private static Long toLong(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Number n) {
      return n.longValue();
    }
    if (v instanceof String s && !s.isBlank()) {
      try {
        return Long.parseLong(s);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  /**
   * 进程内位点存储,模拟落在平台库(与业务库分离)。{@link #failNextAdvance()} 注入一次 advance 崩溃,复现"业务已 commit、位点未推进"的补偿窗口。
   */
  private static final class CrashInjectingPositionStore implements ProcessingPositionStore {
    private final Map<String, ProcessingPosition> rows = new ConcurrentHashMap<>();
    private boolean failNextAdvance;

    void failNextAdvance() {
      this.failNextAdvance = true;
    }

    private static String key(String tenantId, long instanceId, ProcessingStage stage) {
      return tenantId + '|' + instanceId + '|' + stage.code();
    }

    @Override
    public ProcessingPosition load(String tenantId, long instanceId, ProcessingStage stage) {
      return rows.getOrDefault(key(tenantId, instanceId, stage), ProcessingPosition.empty());
    }

    @Override
    public void advance(
        String tenantId,
        long instanceId,
        ProcessingStage stage,
        String newMarker,
        long processedCountIncrement) {
      if (failNextAdvance) {
        failNextAdvance = false;
        throw new IllegalStateException("injected crash before position advance");
      }
      ProcessingPosition current = load(tenantId, instanceId, stage);
      if (current.completed()) {
        return; // completed 后不回写(与 DB UPSERT 守护一致)
      }
      rows.put(
          key(tenantId, instanceId, stage),
          new ProcessingPosition(
              newMarker, current.processedCount() + processedCountIncrement, false));
    }

    @Override
    public void markCompleted(String tenantId, long instanceId, ProcessingStage stage) {
      ProcessingPosition current = load(tenantId, instanceId, stage);
      rows.put(
          key(tenantId, instanceId, stage),
          new ProcessingPosition(current.positionMarker(), current.processedCount(), true));
    }

    @Override
    public void deleteAllStages(String tenantId, long instanceId) {
      rows.keySet().removeIf(k -> k.startsWith(tenantId + '|' + instanceId + '|'));
    }
  }

  /** 明确非幂等(NONE)且 loadChunk 真写库 —— 只在前置校验失效时才会污染表,便于断言拒跑成立。 */
  private static final class WritingNonIdempotentPlugin implements ImportLoadPlugin {
    private final JdbcTemplate jdbc;

    WritingNonIdempotentPlugin(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public String id() {
      return WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED;
    }

    @Override
    public IdempotencyCapability idempotencyCapability() {
      return IdempotencyCapability.NONE;
    }

    @Override
    public int loadChunk(ImportLoadContext context, List<Map<String, Object>> records) {
      for (Map<String, Object> r : records) {
        final String insertBizCkptCustomerSql = """
            INSERT INTO biz.ckpt_customer(tenant_id,customer_no,customer_name) VALUES (?,?,?)
        """;
        jdbc.update(
            insertBizCkptCustomerSql,
            context.tenantId(),
            String.valueOf(r.get("customer_no")),
            String.valueOf(r.get("name")));
      }
      return records.size();
    }
  }
}
