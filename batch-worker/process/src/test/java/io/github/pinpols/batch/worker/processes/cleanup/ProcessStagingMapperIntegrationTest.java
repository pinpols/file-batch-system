package io.github.pinpols.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.processes.mapper.business.ProcessStagingMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("Process 清理保留跨分区及并发移动的新数据")
class ProcessStagingMapperIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.create();

  private JdbcTemplate jdbcTemplate;
  private ProcessStagingMapper mapper;
  private SqlSession sqlSession;
  private SqlSessionFactory sqlSessionFactory;
  private DataSource dataSource;

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
    dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("drop schema if exists batch cascade");
    jdbcTemplate.execute("create schema batch");
    jdbcTemplate.execute("""
        create table batch.process_staging (
          batch_key text not null,
          row_seq bigint not null,
          tenant_id text not null,
          target_schema text not null,
          target_table text not null,
          payload jsonb not null,
          staged_at timestamptz not null
        ) partition by range (staged_at)
        """);
    jdbcTemplate.execute("""
        create table batch.process_staging_old partition of batch.process_staging
        for values from (minvalue) to ('2026-10-14 00:00:00+00')
        """);
    jdbcTemplate.execute("""
        create table batch.process_staging_current partition of batch.process_staging
        for values from ('2026-10-14 00:00:00+00') to (maxvalue)
        """);
    jdbcTemplate.update("""
        insert into batch.process_staging values
          ('old-a', 1, 't1', 'biz', 'target', '{}', '2026-10-12 00:00:00+00'),
          ('old-b', 2, 't1', 'biz', 'target', '{}', '2026-10-13 00:00:00+00'),
          ('fresh', 2, 't1', 'biz', 'target', '{}', now())
        """);

    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(dataSource);
    factoryBean.setMapperLocations(
        new ClassPathResource("mapper/business/ProcessStagingMapper.xml"));
    sqlSessionFactory = factoryBean.getObject();
    sqlSession = sqlSessionFactory.openSession(true);
    mapper = sqlSession.getMapper(ProcessStagingMapper.class);
  }

  @DisplayName("孤儿清理不误删其它分区相同 ctid 的新行")
  @Test
  void shouldOrphanCleanupDoesNotDeleteFreshRowWithSameCtidInAnotherPartition() {
    assertThat(
            jdbcTemplate.queryForList(
                "select ctid::text from batch.process_staging where batch_key in ('old-a','fresh') order by batch_key"))
        .containsExactly(java.util.Map.of("ctid", "(0,1)"), java.util.Map.of("ctid", "(0,1)"));

    int deleted = mapper.deleteOrphansOlderThan(24, 1);

    assertThat(deleted).isEqualTo(1);
    assertThat(jdbcTemplate.queryForList(
            "select batch_key from batch.process_staging order by batch_key"))
        .containsExactly(
            java.util.Map.of("batch_key", "fresh"), java.util.Map.of("batch_key", "old-b"));
  }

  @DisplayName("并发更新将行移入新分区后清理不误删")
  @Test
  void shouldCleanupDoesNotDeleteRowMovedToFreshPartitionByConcurrentUpdate() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch rowLocked = new CountDownLatch(1);
    CountDownLatch allowMove = new CountDownLatch(1);
    TransactionTemplate updateTransaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    try {
      Future<?> moveRow = executor.submit(() -> updateTransaction.execute(status -> {
        jdbcTemplate.queryForObject(
            "select batch_key from batch.process_staging where batch_key='old-a' for update",
            String.class);
        rowLocked.countDown();
        try {
          if (!allowMove.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("timed out waiting to move locked staging row");
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while holding staging row lock", exception);
        }
        jdbcTemplate.update(
            "update batch.process_staging set staged_at=now() where batch_key='old-a'");
        return null;
      }));
      assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

      Future<Integer> cleanup = executor.submit(() -> {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
          return session.getMapper(ProcessStagingMapper.class).deleteOrphansOlderThan(24, 1);
        }
      });
      assertThat(cleanup.get(2, TimeUnit.SECONDS)).isEqualTo(1);
      allowMove.countDown();

      moveRow.get(5, TimeUnit.SECONDS);
      assertThat(jdbcTemplate.queryForList(
              "select batch_key from batch.process_staging where batch_key='old-a'"))
          .containsExactly(java.util.Map.of("batch_key", "old-a"));
      assertThat(jdbcTemplate.queryForObject(
              "select tableoid::regclass::text from batch.process_staging where batch_key='old-a'",
              String.class))
          .isEqualTo("batch.process_staging_current");
    } finally {
      allowMove.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @org.junit.jupiter.api.AfterEach
  void closeSession() {
    sqlSession.close();
  }
}
