package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("实际执行入口 SQL：首次时间、生命周期围栏与冷热字段")
class JobExecutionStartMapperIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static final Instant DISPATCHED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static JdbcTemplate jdbcTemplate;
  private static SqlSessionFactory sessionFactory;

  @BeforeAll
  static void initialize() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    String createTablesSql = """
        CREATE SCHEMA batch;
        CREATE SCHEMA archive;
        CREATE TABLE batch.job_instance (
            id BIGSERIAL PRIMARY KEY, tenant_id VARCHAR(64), instance_status VARCHAR(32),
            started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ,
            updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
        CREATE TABLE archive.job_instance_archive (LIKE batch.job_instance INCLUDING DEFAULTS)
        """;
    jdbcTemplate.execute(createTablesSql);
    ClassPathResource migration =
        new ClassPathResource("db/migration/V354__job_execution_start_time.sql");
    ResourceDatabasePopulator populator = new ResourceDatabasePopulator(migration);
    populator.execute(dataSource);
    JdbcTransactionFactory transactionFactory = new JdbcTransactionFactory();
    Environment environment = new Environment("execution-start", transactionFactory, dataSource);
    Configuration configuration = new Configuration(environment);
    configuration.addMapper(JobInstanceMapper.class);
    String resource = "mapper/JobInstanceMapper.xml";
    try (InputStream input = Resources.getResourceAsStream(resource)) {
      XMLMapperBuilder builder =
          new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments());
      builder.parse();
    }
    sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  @DisplayName("实际开始独立记录，原始派发时间不被覆盖，重复确认保留首次值")
  void shouldKeepDispatchAndFirstExecutionSeparate() {
    Fixture fixture = insertInstance(JobInstanceStatus.RUNNING, null);
    try (SqlSession session = sessionFactory.openSession(true)) {
      JobInstanceMapper mapper = session.getMapper(JobInstanceMapper.class);
      int marked = mapper.markExecutionStarted(fixture.tenantId(), fixture.id());
      assertThat(marked).isOne();
      Instant firstStart = executionStartedAt(fixture);
      assertThat(firstStart).isAfter(DISPATCHED_AT);
      String selectDispatchSql = """
          SELECT started_at FROM batch.job_instance WHERE tenant_id = ? AND id = ?
          """;
      Timestamp dispatch = jdbcTemplate.queryForObject(
          selectDispatchSql, Timestamp.class, fixture.tenantId(), fixture.id());
      assertThat(dispatch.toInstant()).isEqualTo(DISPATCHED_AT);
      int repeated = mapper.markExecutionStarted(fixture.tenantId(), fixture.id());
      assertThat(repeated).isOne();
      Instant repeatedStart = executionStartedAt(fixture);
      assertThat(repeatedStart).isEqualTo(firstStart);
    }
  }

  @Test
  @DisplayName("SQL 的可推进状态集合与生命周期枚举逐项对照，所有终态均拒绝开始")
  void shouldMatchActiveLifecycleStatusesWithoutRevivingTerminalJobs() {
    try (SqlSession session = sessionFactory.openSession(true)) {
      JobInstanceMapper mapper = session.getMapper(JobInstanceMapper.class);
      for (JobInstanceStatus status : JobInstanceStatus.values()) {
        Fixture fixture = insertInstance(status, null);
        int marked = mapper.markExecutionStarted(fixture.tenantId(), fixture.id());
        boolean active = JobInstanceStatus.activeCodes().contains(status.code());
        assertThat(marked).as("状态 %s", status).isEqualTo(active ? 1 : 0);
        Instant startedAt = executionStartedAt(fixture);
        if (active) assertThat(startedAt).isNotNull();
        else assertThat(startedAt).isNull();
      }
    }
  }

  @Test
  @DisplayName("即使状态尚未同步，已有完成时间也不能再记录开始")
  void shouldFenceAJobThatAlreadyHasFinishTime() {
    Fixture fixture = insertInstance(JobInstanceStatus.RUNNING, DISPATCHED_AT.plusSeconds(10));
    try (SqlSession session = sessionFactory.openSession(true)) {
      JobInstanceMapper mapper = session.getMapper(JobInstanceMapper.class);
      int marked = mapper.markExecutionStarted(fixture.tenantId(), fixture.id());
      assertThat(marked).isZero();
      Instant executionStart = executionStartedAt(fixture);
      assertThat(executionStart).isNull();
    }
  }

  @Test
  @DisplayName("另一租户不能写入执行起点")
  void shouldRejectCrossTenantExecutionStart() {
    Fixture fixture = insertInstance(JobInstanceStatus.RUNNING, null);
    try (SqlSession session = sessionFactory.openSession(true)) {
      JobInstanceMapper mapper = session.getMapper(JobInstanceMapper.class);
      int marked = mapper.markExecutionStarted("another-tenant", fixture.id());
      assertThat(marked).isZero();
      Instant executionStart = executionStartedAt(fixture);
      assertThat(executionStart).isNull();
    }
  }

  @Test
  @DisplayName("冷热表均具备实际执行时间列，复制后不丢失时间精度")
  void shouldPreserveExecutionTimeInColdMirror() {
    Fixture fixture = insertInstance(JobInstanceStatus.RUNNING, null);
    try (SqlSession session = sessionFactory.openSession(true)) {
      JobInstanceMapper mapper = session.getMapper(JobInstanceMapper.class);
      mapper.markExecutionStarted(fixture.tenantId(), fixture.id());
    }
    Instant hot = executionStartedAt(fixture);
    String copySql = """
        INSERT INTO archive.job_instance_archive (
            id, tenant_id, instance_status, started_at, execution_started_at, finished_at, updated_at)
        SELECT id, tenant_id, instance_status, started_at, execution_started_at, finished_at, updated_at
        FROM batch.job_instance WHERE tenant_id = ? AND id = ?
        """;
    jdbcTemplate.update(copySql, fixture.tenantId(), fixture.id());
    String selectColdSql = """
        SELECT execution_started_at FROM archive.job_instance_archive WHERE tenant_id = ? AND id = ?
        """;
    Timestamp cold = jdbcTemplate.queryForObject(
        selectColdSql, Timestamp.class, fixture.tenantId(), fixture.id());
    assertThat(cold.toInstant()).isEqualTo(hot);
  }

  private Fixture insertInstance(JobInstanceStatus status, Instant finishedAt) {
    String tenant = "execution-start-" + UUID.randomUUID().toString().replace("-", "");
    String insertSql = """
        INSERT INTO batch.job_instance (tenant_id, instance_status, started_at, finished_at)
        VALUES (?, ?, ?, ?) RETURNING id
        """;
    Timestamp dispatch = Timestamp.from(DISPATCHED_AT);
    Timestamp finish = finishedAt == null ? null : Timestamp.from(finishedAt);
    Long id =
        jdbcTemplate.queryForObject(insertSql, Long.class, tenant, status.code(), dispatch, finish);
    return new Fixture(tenant, id);
  }

  private Instant executionStartedAt(Fixture fixture) {
    String selectSql = """
        SELECT execution_started_at FROM batch.job_instance WHERE tenant_id = ? AND id = ?
        """;
    Timestamp timestamp =
        jdbcTemplate.queryForObject(selectSql, Timestamp.class, fixture.tenantId(), fixture.id());
    return timestamp == null ? null : timestamp.toInstant();
  }

  private record Fixture(String tenantId, long id) {}
}
