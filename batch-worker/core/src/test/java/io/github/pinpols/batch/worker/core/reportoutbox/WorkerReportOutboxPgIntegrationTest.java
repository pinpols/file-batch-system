package io.github.pinpols.batch.worker.core.reportoutbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestContainerImages;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.mapper.WorkerReportOutboxPgMapper;
import java.io.InputStream;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code WorkerReportOutboxRepository} 的 PLATFORM_PG(文档默认后端)真 PG 闭环护栏。
 *
 * <p>此前 PLATFORM_PG 路径零 IT(只有 SQLITE 有覆盖),本类用真实 PG(Testcontainers)+ 真实 {@code
 * WorkerReportOutboxPgMapper.xml} 跑「入队 → 出队(claim NEW→PUBLISHING)→ 清理(delete /
 * resetStalePublishing)」 基本闭环,并以 {@code stats()} 计数交叉验证状态流转。DDL 复用 V96 {@code
 * batch.worker_report_outbox}。
 */
@DisplayName("WorkerReportOutbox PLATFORM_PG 入队/出队/清理闭环")
class WorkerReportOutboxPgIntegrationTest {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(TestContainerImages.POSTGRES);

  private static final long STALE_WINDOW = 120_000L;

  private JdbcTemplate jdbcTemplate;
  private SqlSession session;
  private WorkerReportOutboxRepository repository;

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
    DataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);

    jdbcTemplate.execute("drop schema if exists batch cascade");
    jdbcTemplate.execute("create schema batch");
    // V96 batch.worker_report_outbox DDL(平台 PG,worker 读写)。
    jdbcTemplate.execute(
        """
        create table batch.worker_report_outbox (
          id bigserial primary key,
          tenant_id varchar(64) not null,
          task_id bigint not null,
          partition_invocation_id varchar(128),
          trace_id varchar(128),
          payload_json jsonb not null,
          publish_status varchar(32) not null,
          attempt_count integer not null,
          next_attempt_at bigint not null,
          created_at bigint not null,
          updated_at bigint not null,
          constraint uq_worker_report_outbox_tenant_task unique (tenant_id, task_id)
        )
        """);

    Configuration configuration =
        new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
    // 每语句独立缓存:claim 是 <select> 形的 UPDATE...RETURNING,不会驱逐一级缓存,
    // 否则后续 countByStatus 命中陈旧计数(生产用短事务/独立 session,无此问题)。
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

    repository =
        new WorkerReportOutboxRepository(
            new WorkerReportOutboxProperties(),
            WorkerReportOutboxDialect.POSTGRESQL,
            pgMapper,
            null,
            null);
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
    repository.delete(claimed.get().id());

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

  private long nowMinusStale() {
    return System.currentTimeMillis() - STALE_WINDOW;
  }
}
