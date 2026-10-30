package io.github.pinpols.batch.worker.core.reportoutbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.mapper.WorkerReportOutboxPgMapper;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.SqliteSessionFactorySupport;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.WorkerReportOutboxSqliteMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code WorkerReportOutboxRepository} 的 PLATFORM_PG(文档默认后端)真 PG 闭环护栏。
 *
 * <p>此前 PLATFORM_PG 路径零 IT(只有 SQLITE 有覆盖),本类用真实 PG(Testcontainers)+ 真实 {@code
 * WorkerReportOutboxPgMapper.xml} 跑「入队 → 出队(claim NEW→PUBLISHING)→ 清理(delete /
 * resetStalePublishing)」 基本闭环,并以 {@code stats()} 计数交叉验证状态流转。DDL通过真实Flyway执行V96/V360,SQLite使用与运行时相同的结构初始化。
 */
@DisplayName("Worker回报发件箱: PostgreSQL与SQLite的执行身份、领取隔离及持久恢复")
class WorkerReportOutboxPgIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.create();

  private static final long STALE_WINDOW = 120_000L;

  private JdbcTemplate jdbcTemplate;
  private SqlSession session;
  private WorkerReportOutboxRepository repository;
  private WorkerReportOutboxProperties properties;

  @TempDir
  Path temporaryDirectory;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @BeforeEach
  void setUp() throws Exception {
    DataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);

    final String dropSql = """
        drop schema if exists batch cascade
    """;
    jdbcTemplate.execute(dropSql);
    final String createSql = """
        create schema batch
    """;
    jdbcTemplate.execute(createSql);
    Flyway migrations = configuredFlyway(dataSource, MigrationVersion.LATEST);
    migrations.migrate();

    Configuration configuration =
        new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
    // 每条断言读取独立状态快照，避免测试长会话复用缓存；生产使用短事务。
    // 领取SQL已显式声明affectData与flushCache，保留UPDATE...RETURNING的写事务语义。
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    try (InputStream xml = Resources.getResourceAsStream("mapper/WorkerReportOutboxPgMapper.xml")) {
      new XMLMapperBuilder(
              xml,
              configuration,
              "mapper/WorkerReportOutboxPgMapper.xml",
              configuration.getSqlFragments())
          .parse();
    }
    SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    session = sqlSessionFactory.openSession(true); // autocommit：单线程测试无需显式事务
    WorkerReportOutboxPgMapper pgMapper = session.getMapper(WorkerReportOutboxPgMapper.class);

    properties = new WorkerReportOutboxProperties();
    repository = new WorkerReportOutboxRepository(
        properties, WorkerReportOutboxDialect.POSTGRESQL, pgMapper, null, null);
  }

  @AfterEach
  void tearDown() {
    if (session != null) {
      session.close();
    }
  }

  private TaskExecutionReport report(long taskId, String tenantId) {
    TaskExecutionReport r = new TaskExecutionReport();
    r.setTaskId(taskId);
    r.setTenantId(tenantId);
    r.setWorkerId("worker-it");
    r.setSuccess(true);
    r.setResultSummary("ok");
    return r;
  }

  @Test
  @DisplayName("入队 → 出队 → 删除:状态计数逐步流转,最终清空")
  void enqueueClaimDelete_fullLoop() {
    // arrange + act: 入队一条
    repository.upsert(report(4242L, "t1"));

    // assert: NEW=1
    WorkerReportOutboxStats afterEnqueue = repository.stats(nowMinusStale());
    assertThat(afterEnqueue.newCount()).isEqualTo(1L);
    assertThat(afterEnqueue.publishingCount()).isZero();

    // act: 出队(NEW→PUBLISHING)
    long now = System.currentTimeMillis();
    Optional<WorkerReportOutboxRow> claimed = repository.claimNext(now);

    // assert: 拿到行 + payload 可反序列化回原报告 + 状态转 PUBLISHING
    assertThat(claimed).isPresent();
    TaskExecutionReport roundTrip = repository.deserializePayload(claimed.get().payloadJson());
    assertThat(roundTrip.getTaskId()).isEqualTo(4242L);
    assertThat(roundTrip.getTenantId()).isEqualTo("t1");
    WorkerReportOutboxStats afterClaim = repository.stats(nowMinusStale());
    assertThat(afterClaim.newCount()).isZero();
    assertThat(afterClaim.publishingCount()).isEqualTo(1L);

    // act: 清理(投递成功后删除)
    repository.delete(claimed.get());

    // assert: 全空
    WorkerReportOutboxStats afterDelete = repository.stats(nowMinusStale());
    assertThat(afterDelete.newCount()).isZero();
    assertThat(afterDelete.publishingCount()).isZero();
  }

  @Test
  @DisplayName("claimNext 无可投递行时返回空")
  void claimNext_returnsEmptyWhenNothingDue() {
    assertThat(repository.claimNext(System.currentTimeMillis())).isEmpty();
  }

  @Test
  @DisplayName("resetStalePublishing:陈旧 PUBLISHING 行回退为 NEW 可重投")
  void resetStalePublishing_recoversStuckRows() {
    // arrange: 入队并出队,使行处于 PUBLISHING
    repository.upsert(report(7L, "t1"));
    Optional<WorkerReportOutboxRow> claimed = repository.claimNext(System.currentTimeMillis());
    assertThat(claimed).isPresent();
    assertThat(repository.stats(nowMinusStale()).publishingCount()).isEqualTo(1L);

    // act: 以「未来」cutoff 让当前 PUBLISHING 行判定为陈旧并回退
    int reset = repository.resetStalePublishing(System.currentTimeMillis() + 60_000L);

    // assert: 回退 1 行,状态回 NEW,可再次 claim
    assertThat(reset).isEqualTo(1);
    WorkerReportOutboxStats afterReset = repository.stats(nowMinusStale());
    assertThat(afterReset.newCount()).isEqualTo(1L);
    assertThat(afterReset.publishingCount()).isZero();
    assertThat(repository.claimNext(System.currentTimeMillis())).isPresent();
  }

  private void useStorage(WorkerReportOutboxStorage storage) throws Exception {
    if (storage == WorkerReportOutboxStorage.PLATFORM_PG) return;
    session.close();
    Path database = Files.createTempFile(temporaryDirectory, "report-outbox-", ".db");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.sqlite.JDBC");
    dataSource.setUrl("jdbc:sqlite:" + database);
    jdbcTemplate = new JdbcTemplate(dataSource);
    SqlSessionFactory factory = SqliteSessionFactorySupport.createSqlSessionFactory(dataSource);
    factory.getConfiguration().setLocalCacheScope(LocalCacheScope.STATEMENT);
    session = factory.openSession(true);
    WorkerReportOutboxSqliteMapper mapper = session.getMapper(WorkerReportOutboxSqliteMapper.class);
    properties.setStorage(storage);
    repository = new WorkerReportOutboxRepository(
        properties, WorkerReportOutboxDialect.SQLITE, null, mapper, jdbcTemplate);
  }

  @ParameterizedTest
  @EnumSource(WorkerReportOutboxStorage.class)
  @DisplayName("新回报覆盖同任务记录后，旧投递确认不能删除新载荷")
  void shouldKeepNewPayload_whenOldDeliveryAcknowledges(WorkerReportOutboxStorage storage)
      throws Exception {
    useStorage(storage);
    TaskExecutionReport original = report(91L, "tenant-owner");
    original.setPartitionInvocationId("inv-same");
    repository.upsert(original);
    WorkerReportOutboxRow oldClaim =
        repository.claimNext(System.currentTimeMillis()).orElseThrow();
    TaskExecutionReport replacement = report(91L, "tenant-owner");
    replacement.setPartitionInvocationId("inv-same");
    replacement.setResultSummary("replacement");
    repository.upsert(replacement);

    repository.delete(oldClaim);

    assertThat(repository.stats(0).newCount()).isOne();
    WorkerReportOutboxRow newClaim =
        repository.claimNext(System.currentTimeMillis()).orElseThrow();
    assertThat(repository.deserializePayload(newClaim.payloadJson()).getResultSummary())
        .isEqualTo("replacement");
  }

  @ParameterizedTest
  @EnumSource(WorkerReportOutboxStorage.class)
  @DisplayName("陈旧领取恢复并再次领取后，旧确认不能删除当前投递")
  void shouldKeepReclaimedDelivery_whenStaleOwnerAcknowledges(WorkerReportOutboxStorage storage)
      throws Exception {
    useStorage(storage);
    repository.upsert(report(92L, "tenant-owner"));
    WorkerReportOutboxRow oldClaim =
        repository.claimNext(System.currentTimeMillis()).orElseThrow();
    assertThat(repository.resetStalePublishing(Long.MAX_VALUE)).isOne();
    WorkerReportOutboxRow current =
        repository.claimNext(System.currentTimeMillis()).orElseThrow();

    repository.delete(oldClaim);

    assertThat(repository.stats(0).publishingCount()).isOne();
    repository.delete(current);
    assertThat(repository.stats(0).publishingCount()).isZero();
  }

  @ParameterizedTest
  @EnumSource(WorkerReportOutboxStorage.class)
  @DisplayName("重新领取后，旧失败不能消费当前尝试次数或修改其状态")
  void shouldKeepReclaimedAttempt_whenStaleOwnerFails(WorkerReportOutboxStorage storage)
      throws Exception {
    useStorage(storage);
    properties.setMaxPublishAttempts(1);
    repository.upsert(report(93L, "tenant-owner"));
    WorkerReportOutboxRow oldClaim =
        repository.claimNext(System.currentTimeMillis()).orElseThrow();
    assertThat(repository.resetStalePublishing(Long.MAX_VALUE)).isOne();
    repository.claimNext(System.currentTimeMillis()).orElseThrow();

    repository.recordFailure(
        oldClaim, System.currentTimeMillis(), new IllegalStateException("old HTTP failed"));

    assertThat(repository.stats(0).publishingCount()).isOne();
    assertThat(repository.stats(0).giveUpCount()).isZero();
  }

  @ParameterizedTest
  @EnumSource(WorkerReportOutboxStorage.class)
  @DisplayName("新载荷已入队并领取后，旧载荷解析失败不能放弃新回报")
  void shouldKeepNewPayload_whenOldPayloadIsMarkedCorrupt(WorkerReportOutboxStorage storage)
      throws Exception {
    useStorage(storage);
    repository.upsert(report(94L, "tenant-owner"));
    WorkerReportOutboxRow oldClaim =
        repository.claimNext(System.currentTimeMillis()).orElseThrow();
    TaskExecutionReport replacement = report(94L, "tenant-owner");
    replacement.setResultSummary("replacement");
    repository.upsert(replacement);
    repository.claimNext(System.currentTimeMillis()).orElseThrow();

    repository.markGiveUp(oldClaim, "CORRUPT_PAYLOAD");

    assertThat(repository.stats(0).publishingCount()).isOne();
    assertThat(repository.stats(0).giveUpCount()).isZero();
  }

  @ParameterizedTest
  @EnumSource(WorkerReportOutboxStorage.class)
  @DisplayName("不同执行尝试的回报乱序入队时，两条未投递载荷均须保留")
  void shouldPreserveBothInvocations_whenOlderAttemptArrivesLast(WorkerReportOutboxStorage storage)
      throws Exception {
    useStorage(storage);
    TaskExecutionReport current = report(95L, "tenant-owner");
    current.setPartitionInvocationId("inv-new");
    repository.upsert(current);
    TaskExecutionReport late = report(95L, "tenant-owner");
    late.setPartitionInvocationId("inv-old");
    repository.upsert(late);

    assertThat(repository.stats(0).newCount()).isEqualTo(2);
    WorkerReportOutboxRow first =
        repository.claimNext(System.currentTimeMillis()).orElseThrow();
    assertThat(repository.deserializePayload(first.payloadJson()).getPartitionInvocationId())
        .isEqualTo("inv-new");
    repository.delete(first);
    WorkerReportOutboxRow second =
        repository.claimNext(System.currentTimeMillis()).orElseThrow();
    assertThat(repository.deserializePayload(second.payloadJson()).getPartitionInvocationId())
        .isEqualTo("inv-old");
    repository.delete(second);
    assertThat(repository.stats(0).newCount()).isZero();
  }

  @ParameterizedTest
  @EnumSource(WorkerReportOutboxStorage.class)
  @DisplayName("旧协议空执行身份的重复回报仍只保留一条待投记录")
  void shouldDeduplicateLegacyNullInvocation_whenRepeated(WorkerReportOutboxStorage storage)
      throws Exception {
    useStorage(storage);
    repository.upsert(report(96L, "tenant-owner"));
    repository.upsert(report(96L, "tenant-owner"));
    assertThat(repository.stats(0).newCount()).isOne();
  }

  @ParameterizedTest
  @EnumSource(WorkerReportOutboxStorage.class)
  @DisplayName("当前领取失败后正常退避重投，达到预算才放弃该执行尝试")
  void shouldRetryAndGiveUpOnlyCurrentAttempt_whenPublishFails(WorkerReportOutboxStorage storage)
      throws Exception {
    useStorage(storage);
    properties.setMaxPublishAttempts(2);
    properties.setInitialBackoffMillis(1);
    properties.setJitterMillis(0);
    repository.upsert(report(97L, "tenant-owner"));
    long now = System.currentTimeMillis();
    WorkerReportOutboxRow first = repository.claimNext(now).orElseThrow();
    repository.recordFailure(first, now, new IllegalStateException("HTTP unavailable"));
    assertThat(repository.stats(0).newCount()).isOne();
    assertThat(repository.stats(0).giveUpCount()).isZero();
    WorkerReportOutboxRow second = repository.claimNext(now + 2).orElseThrow();
    assertThat(second.claimToken()).isNotEqualTo(first.claimToken());

    repository.recordFailure(second, now + 3, new IllegalStateException("still unavailable"));

    assertThat(repository.stats(0).giveUpCount()).isOne();
    assertThat(repository.stats(0).newCount()).isZero();
    assertThat(repository.stats(0).publishingCount()).isZero();
  }

  @ParameterizedTest
  @EnumSource(WorkerReportOutboxStorage.class)
  @DisplayName("极大退避不得溢出并提前重投")
  void shouldSaturateRetryTime_whenExponentialBackoffOverflows(WorkerReportOutboxStorage storage)
      throws Exception {
    useStorage(storage);
    long initial = Long.MAX_VALUE / 2 + 1;
    properties.setInitialBackoffMillis(initial);
    properties.setMaxBackoffMillis(Long.MAX_VALUE);
    properties.setJitterMillis(0);
    repository.upsert(report(101L, "tenant-owner"));
    long now = System.currentTimeMillis();
    WorkerReportOutboxRow first = repository.claimNext(now).orElseThrow();
    repository.recordFailure(first, now, new IllegalStateException("unavailable"));
    long secondTime = now + initial;
    WorkerReportOutboxRow second = repository.claimNext(secondTime).orElseThrow();
    repository.recordFailure(second, secondTime, new IllegalStateException("still unavailable"));

    assertThat(repository.claimNext(Long.MAX_VALUE - 1)).isEmpty();
    assertThat(repository.claimNext(Long.MAX_VALUE)).isPresent();
  }

  @ParameterizedTest
  @EnumSource(WorkerReportOutboxStorage.class)
  @DisplayName("最大抖动配置不得中断失败记录或生成过去的重试时间")
  void shouldRecordFailure_whenJitterIsLongMax(WorkerReportOutboxStorage storage) throws Exception {
    useStorage(storage);
    properties.setInitialBackoffMillis(1);
    properties.setJitterMillis(Long.MAX_VALUE);
    repository.upsert(report(102L, "tenant-owner"));
    long now = System.currentTimeMillis();
    WorkerReportOutboxRow claim = repository.claimNext(now).orElseThrow();

    repository.recordFailure(claim, now, new IllegalStateException("unavailable"));

    assertThat(repository.claimNext(now - 1)).isEmpty();
    assertThat(repository.stats(0).newCount()).isOne();
  }

  @ParameterizedTest
  @EnumSource(WorkerReportOutboxStorage.class)
  @DisplayName("当前领取载荷不可投递时，允许将其标记为人工处理")
  void shouldGiveUpCorruptPayloadOnlyForCurrentClaim_whenOwned(WorkerReportOutboxStorage storage)
      throws Exception {
    useStorage(storage);
    repository.upsert(report(98L, "tenant-owner"));
    WorkerReportOutboxRow current =
        repository.claimNext(System.currentTimeMillis()).orElseThrow();

    repository.markGiveUp(current, "CORRUPT_PAYLOAD");

    assertThat(repository.stats(0).giveUpCount()).isOne();
    assertThat(repository.stats(0).publishingCount()).isZero();
  }

  @Test
  @DisplayName("同版本SQLite文件重新打开后保留待投载荷并可正常确认")
  void shouldPreservePendingPayload_whenCurrentSqliteStoreReopens() throws Exception {
    useStorage(WorkerReportOutboxStorage.SQLITE);
    TaskExecutionReport expected = report(99L, "tenant-owner");
    expected.setPartitionInvocationId("inv-restart");
    repository.upsert(expected);
    DataSource dataSource = jdbcTemplate.getDataSource();
    SqlSessionFactory factory = SqliteSessionFactorySupport.createSqlSessionFactory(dataSource);
    try (SqlSession restarted = factory.openSession(true)) {
      WorkerReportOutboxSqliteMapper mapper =
          restarted.getMapper(WorkerReportOutboxSqliteMapper.class);
      WorkerReportOutboxRepository reopened = new WorkerReportOutboxRepository(
          properties, WorkerReportOutboxDialect.SQLITE, null, mapper, jdbcTemplate);
      WorkerReportOutboxRow row = reopened.claimNext(System.currentTimeMillis()).orElseThrow();
      assertThat(reopened.deserializePayload(row.payloadJson()).getPartitionInvocationId())
          .isEqualTo("inv-restart");
      reopened.delete(row);
      assertThat(reopened.stats(0).publishingCount()).isZero();
    }
  }

  @Test
  @DisplayName("旧SQLite结构失败关闭并保留原载荷，不自动清表或搬运旧队列")
  void shouldRejectLegacySqliteSchemaWithoutChangingPayload() throws Exception {
    properties.setStorage(WorkerReportOutboxStorage.SQLITE);
    Path file = Files.createTempFile(temporaryDirectory, "legacy-outbox-", ".db");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.sqlite.JDBC");
    dataSource.setUrl("jdbc:sqlite:" + file);
    JdbcTemplate legacyJdbc = new JdbcTemplate(dataSource);
    String legacySchemaSql = """
        CREATE TABLE worker_report_outbox (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          tenant_id TEXT NOT NULL,
          task_id INTEGER NOT NULL,
          partition_invocation_id TEXT,
          trace_id TEXT,
          payload_json TEXT NOT NULL,
          publish_status TEXT NOT NULL,
          attempt_count INTEGER NOT NULL,
          next_attempt_at INTEGER NOT NULL,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL,
          UNIQUE(tenant_id, task_id)
        )
        """;
    legacyJdbc.execute(legacySchemaSql);
    String seedSql = """
        INSERT INTO worker_report_outbox
          (tenant_id, task_id, payload_json, publish_status, attempt_count, next_attempt_at, created_at, updated_at)
        VALUES (?, ?, ?, 'NEW', 0, 0, 0, 0)
        """;
    legacyJdbc.update(seedSql, "tenant-owner", 100L, "{\"evidence\":\"preserve\"}");
    SqlSessionFactory factory = SqliteSessionFactorySupport.createSqlSessionFactory(dataSource);
    try (SqlSession legacy = factory.openSession(true)) {
      WorkerReportOutboxSqliteMapper mapper =
          legacy.getMapper(WorkerReportOutboxSqliteMapper.class);
      assertThatThrownBy(() -> new WorkerReportOutboxRepository(
              properties, WorkerReportOutboxDialect.SQLITE, null, mapper, legacyJdbc))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("schema is incompatible");
    }
    String payloadSql = "SELECT payload_json FROM worker_report_outbox WHERE task_id = ?";
    assertThat(legacyJdbc.queryForObject(payloadSql, String.class, 100L))
        .isEqualTo("{\"evidence\":\"preserve\"}");
  }

  @Test
  @DisplayName("旧PG队列非空时拒绝新身份迁移，原载荷和唯一约束保持完整")
  void shouldRejectIdentityMigration_whenPreviousQueueContainsData() {
    String dropSql = "DROP SCHEMA batch CASCADE";
    jdbcTemplate.execute(dropSql);
    DataSource dataSource = jdbcTemplate.getDataSource();
    MigrationVersion previousVersion = MigrationVersion.fromVersion("96");
    Flyway previous = configuredFlyway(dataSource, previousVersion);
    previous.migrate();
    String seedSql = """
        INSERT INTO batch.worker_report_outbox
          (tenant_id, task_id, partition_invocation_id, payload_json, publish_status,
           attempt_count, next_attempt_at, created_at, updated_at)
        VALUES (?, ?, ?, ?::jsonb, 'NEW', 0, 0, 0, 0)
        """;
    jdbcTemplate.update(
        seedSql, "tenant-owner", 101L, "inv-preserve", "{\"evidence\":\"preserve\"}");
    Flyway migration = configuredFlyway(dataSource, MigrationVersion.LATEST);

    assertThatThrownBy(migration::migrate)
        .hasStackTraceContaining("V360 requires an empty worker report outbox");

    String payloadSql =
        "SELECT payload_json->>'evidence' FROM batch.worker_report_outbox WHERE task_id = ?";
    assertThat(jdbcTemplate.queryForObject(payloadSql, String.class, 101L)).isEqualTo("preserve");
    String claimColumnSql = """
        SELECT count(*) FROM information_schema.columns
        WHERE table_schema = 'batch' AND table_name = 'worker_report_outbox' AND column_name = 'claim_token'
        """;
    assertThat(jdbcTemplate.queryForObject(claimColumnSql, Integer.class)).isZero();
    String previousIdentitySql = """
        SELECT count(*) FROM pg_constraint
        WHERE conrelid = 'batch.worker_report_outbox'::regclass
          AND conname = 'uq_worker_report_outbox_tenant_task'
        """;
    assertThat(jdbcTemplate.queryForObject(previousIdentitySql, Integer.class)).isOne();
  }

  private static Flyway configuredFlyway(DataSource dataSource, MigrationVersion targetVersion) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas("batch")
        .locations("classpath:db/worker-report-outbox-fixture")
        .target(targetVersion)
        .load();
  }

  private long nowMinusStale() {
    return System.currentTimeMillis() - STALE_WINDOW;
  }
}
