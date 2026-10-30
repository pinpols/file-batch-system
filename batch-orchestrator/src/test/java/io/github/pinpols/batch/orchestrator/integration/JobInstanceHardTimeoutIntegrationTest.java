package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("作业硬超时从实际执行入口计时")
class JobInstanceHardTimeoutIntegrationTest {

  @Test
  @DisplayName("排队不计超时并优先使用实例冻结的执行超时")
  void shouldExcludeQueueTime_andUseFirstExecutionAcrossTasks() throws Exception {
    try (PostgreSQLContainer postgres = TestPostgresContainers.platform()) {
      postgres.start();
      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setURL(postgres.getJdbcUrl());
      dataSource.setUser(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String createSql = """
          CREATE SCHEMA batch;
          CREATE TABLE batch.job_definition (
              id bigint PRIMARY KEY, tenant_id text NOT NULL, timeout_seconds integer NOT NULL);
          CREATE TABLE batch.job_instance (
              id bigint PRIMARY KEY, tenant_id text NOT NULL, job_definition_id bigint NOT NULL,
              instance_status text NOT NULL, started_at timestamptz,
              execution_started_at timestamptz, finished_at timestamptz, params_snapshot jsonb,
              updated_at timestamptz NOT NULL DEFAULT current_timestamp);
          CREATE TABLE batch.job_task (
              tenant_id text NOT NULL, job_instance_id bigint NOT NULL, task_status text NOT NULL)
          """;
      String insertDefinitionSql = """
          INSERT INTO batch.job_definition (id, tenant_id, timeout_seconds) VALUES (1, 't1', 60)
          """;
      String insertInstanceSql = """
          INSERT INTO batch.job_instance
              (id, tenant_id, job_definition_id, instance_status, started_at, execution_started_at)
          VALUES (?, 't1', 1, 'RUNNING', ?, ?)
          """;
      String insertTaskSql = """
          INSERT INTO batch.job_task (tenant_id, job_instance_id, task_status) VALUES ('t1', ?, ?)
          """;
      jdbc.execute(createSql);
      jdbc.update(insertDefinitionSql);
      Instant now = Instant.now();
      Timestamp dispatched = Timestamp.from(now.minusSeconds(3600));
      jdbc.update(insertInstanceSql, 1L, dispatched, Timestamp.from(now));
      jdbc.update(insertInstanceSql, 2L, dispatched, Timestamp.from(now.minusSeconds(120)));
      jdbc.update(insertInstanceSql, 3L, dispatched, null);
      jdbc.update(insertInstanceSql, 4L, dispatched, Timestamp.from(now.minusSeconds(120)));
      jdbc.update(insertInstanceSql, 5L, dispatched, Timestamp.from(now.minusSeconds(120)));
      jdbc.update(insertInstanceSql, 6L, dispatched, Timestamp.from(now.minusSeconds(120)));
      jdbc.update(insertInstanceSql, 7L, dispatched, Timestamp.from(now.minusSeconds(120)));
      String updateSnapshotSql = """
          UPDATE batch.job_instance SET params_snapshot = ?::jsonb WHERE id = ?
          """;
      jdbc.update(updateSnapshotSql, "{\"executionPolicy\":{\"timeoutSeconds\":3600}}", 5L);
      jdbc.update(updateSnapshotSql, "{\"executionPolicy\":{\"timeoutSeconds\":30}}", 6L);
      jdbc.update(updateSnapshotSql, "{\"executionPolicy\":{\"timeoutSeconds\":0}}", 7L);
      for (long id : List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L)) {
        jdbc.update(insertTaskSql, id, "RUNNING");
      }
      jdbc.update(insertTaskSql, 4L, "SUCCESS");

      Configuration configuration = new Configuration(
          new Environment("hard-timeout", new JdbcTransactionFactory(), dataSource));
      configuration.addMapper(JobInstanceMapper.class);
      String resource = "mapper/JobInstanceMapper.xml";
      try (InputStream input = Resources.getResourceAsStream(resource)) {
        new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments())
            .parse();
      }
      SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
      try (SqlSession session = sessionFactory.openSession(true)) {
        JobInstanceMapper mapper = session.getMapper(JobInstanceMapper.class);
        List<Long> ids = mapper.selectTimedOutCandidates(100).stream()
            .map(JobInstanceEntity::getId)
            .toList();
        assertThat(ids).containsExactlyInAnyOrder(2L, 4L, 6L);
      }
    }
  }
}
