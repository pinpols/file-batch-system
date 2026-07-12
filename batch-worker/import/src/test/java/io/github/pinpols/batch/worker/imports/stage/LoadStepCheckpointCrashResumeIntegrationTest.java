package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ImportLoadContext;
import io.github.pinpols.batch.common.plugin.ImportLoadPlugin;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.testing.TestContainerImages;
import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPosition;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingStage;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration.FileProcessing;
import io.github.pinpols.batch.worker.imports.config.JdbcMappedImportSecurityProperties;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.plugin.GenericJdbcMappedImportLoadPlugin;
import io.github.pinpols.batch.worker.imports.plugin.ImportLoadPluginRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
class LoadStepCheckpointCrashResumeIntegrationTest {

  private static final String TENANT = "t1";
  private static final long PIPELINE_INSTANCE_ID = 9101L;
  private static final String BIZ_DATE = "2026-06-02";

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(TestContainerImages.POSTGRES))
          .withDatabaseName("batch_business")
          .withUsername("batch_user")
          .withPassword("batch_pass_123")
          .withUrlParam("sslmode", "disable");

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private GenericJdbcMappedImportLoadPlugin plugin;
  private ObjectMapper objectMapper;
  private PlatformFileRuntimeRepository runtimeRepository;
  private ImportWorkerConfiguration workerConfig;
  private final List<Path> tempPaths = new ArrayList<>();

  @BeforeEach
  void setUp() {
    dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl() + "&stringtype=unspecified");
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    objectMapper = new ObjectMapper();

    JdbcMappedImportSecurityProperties security = new JdbcMappedImportSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setStrictIdempotency(true);
    plugin = new GenericJdbcMappedImportLoadPlugin(dataSource, objectMapper, security);

    jdbcTemplate.execute("DROP SCHEMA IF EXISTS biz CASCADE");
    jdbcTemplate.execute("CREATE SCHEMA biz");
    jdbcTemplate.execute(
        """
        CREATE TABLE biz.ckpt_customer (
          tenant_id text NOT NULL,
          customer_no text NOT NULL,
          customer_name text,
          PRIMARY KEY (tenant_id, customer_no)
        )
        """);

    runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    when(runtimeRepository.toLong(any())).thenAnswer(inv -> toLong(inv.getArgument(0)));
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
  }

  @AfterEach
  void cleanup() throws Exception {
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
    assertThat(
            positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD).positionMarker())
        .isNull();

    // ── Attempt 2:重派,同 pipelineInstanceId,位点仍为空 → 从第 0 行续跑,重做 C1,C2 ──
    ImportStageResult attempt2 = loadStep.execute(newContext(validated));
    assertThat(attempt2.success()).isTrue();
    // 关键断言:C1,C2 被重做,但 ON CONFLICT 幂等 → 各只有一行,不双写;C3,C4 补齐。
    assertThat(customerNos()).containsExactly("C1", "C2", "C3", "C4");
    assertThat(rowCount()).isEqualTo(4);
    // 位点在完整重跑后标记完成
    assertThat(positionStore.load(TENANT, PIPELINE_INSTANCE_ID, ProcessingStage.LOAD).completed())
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

  // ─── helpers ────────────────────────────────────────────────────────────────

  private LoadStep newLoadStep(
      ImportLoadPlugin loadPlugin,
      WorkerCheckpointProperties checkpointProps,
      ProcessingPositionStore positionStore) {
    ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(loadPlugin));
    return new LoadStep(
        registry, runtimeRepository, workerConfig, objectMapper, checkpointProps, positionStore);
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
    return jdbcTemplate.queryForList(
        "SELECT customer_no FROM biz.ckpt_customer ORDER BY customer_no", String.class);
  }

  private int rowCount() {
    Integer n =
        jdbcTemplate.queryForObject("SELECT count(*) FROM biz.ckpt_customer", Integer.class);
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
          key(tenantId, instanceId, stage), ProcessingPosition.completed(current.processedCount()));
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
        jdbc.update(
            "INSERT INTO biz.ckpt_customer(tenant_id,customer_no,customer_name) VALUES (?,?,?)",
            context.tenantId(),
            String.valueOf(r.get("customer_no")),
            String.valueOf(r.get("name")));
      }
      return records.size();
    }
  }
}
