package io.github.pinpols.batch.console.domain.job.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("作业监控策略 mapper 的真实 PostgreSQL 更新语义")
class JobMonitoringPolicyMapperIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  private static JdbcTemplate jdbcTemplate;
  private static SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void startPostgresAndMapper() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("create schema batch");
    jdbcTemplate.execute("create table batch.job_definition (id bigint primary key)");
    jdbcTemplate.execute("""
        create table batch.job_monitoring_policy (
          tenant_id varchar(64) not null,
          job_definition_id bigint not null references batch.job_definition(id) on delete cascade,
          soft_runtime_seconds integer not null,
          soft_runtime_severity varchar(16) not null default 'WARN',
          start_grace_seconds integer not null,
          start_grace_severity varchar(16) not null default 'WARN',
          completion_deadline_seconds integer not null,
          completion_deadline_local_time time,
          completion_deadline_day_offset smallint not null default 0,
          dependency_completion_window_seconds integer not null default 0,
          completion_deadline_severity varchar(16) not null default 'WARN',
          updated_by varchar(64),
          created_at timestamptz not null default current_timestamp,
          updated_at timestamptz not null default current_timestamp,
          completion_deadline_updated_at timestamptz not null default current_timestamp,
          unique (tenant_id, job_definition_id)
        )
        """);
    jdbcTemplate.update("insert into batch.job_definition(id) values (1)");

    org.apache.ibatis.session.Configuration configuration =
        new org.apache.ibatis.session.Configuration(
            new Environment("job-monitoring-policy-it", new JdbcTransactionFactory(), dataSource));
    parseMapper(configuration, "mapper/CommonFragments.xml");
    parseMapper(configuration, "mapper/JobDefinitionMapper.xml");
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @DisplayName("普通作业编辑不重置完成期限基线,修改期限才更新")
  @Test
  void shouldResetLateCompletionBaselineOnlyWhenDeadlineChanges() {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobDefinitionMapper mapper = session.getMapper(JobDefinitionMapper.class);
      mapper.upsertJobMonitoringPolicy(JobMonitoringPolicyUpsertParam.builder()
          .tenantId("tenant-a")
          .jobDefinitionId(1L)
          .softRuntimeSeconds(30)
          .softRuntimeSeverity("WARN")
          .startGraceSeconds(60)
          .startGraceSeverity("WARN")
          .completionDeadlineLocalTime(LocalTime.of(4, 0))
          .completionDeadlineDayOffset(1)
          .dependencyCompletionWindowSeconds(0)
          .completionDeadlineSeverity("WARN")
          .updatedBy("operator")
          .build());
      jdbcTemplate.update(
          "update batch.job_monitoring_policy set completion_deadline_updated_at = current_timestamp - interval '1 hour'");
      OffsetDateTime originalBaseline = readCompletionBaseline();

      mapper.upsertJobMonitoringPolicy(JobMonitoringPolicyUpsertParam.builder()
          .tenantId("tenant-a")
          .jobDefinitionId(1L)
          .softRuntimeSeconds(45)
          .softRuntimeSeverity("ERROR")
          .startGraceSeconds(90)
          .startGraceSeverity("WARN")
          .completionDeadlineLocalTime(LocalTime.of(4, 0))
          .completionDeadlineDayOffset(1)
          .dependencyCompletionWindowSeconds(0)
          .completionDeadlineSeverity("WARN")
          .updatedBy("operator")
          .build());

      assertThat(readCompletionBaseline()).isEqualTo(originalBaseline);
      mapper.upsertJobMonitoringPolicy(JobMonitoringPolicyUpsertParam.builder()
          .tenantId("tenant-a")
          .jobDefinitionId(1L)
          .softRuntimeSeconds(45)
          .softRuntimeSeverity("ERROR")
          .startGraceSeconds(90)
          .startGraceSeverity("WARN")
          .completionDeadlineLocalTime(LocalTime.of(4, 1))
          .completionDeadlineDayOffset(1)
          .dependencyCompletionWindowSeconds(0)
          .completionDeadlineSeverity("CRITICAL")
          .updatedBy("operator")
          .build());
      assertThat(readCompletionBaseline()).isAfter(originalBaseline);

      OffsetDateTime clockBaseline = readCompletionBaseline();
      mapper.upsertJobMonitoringPolicy(JobMonitoringPolicyUpsertParam.builder()
          .tenantId("tenant-a")
          .jobDefinitionId(1L)
          .softRuntimeSeconds(45)
          .softRuntimeSeverity("ERROR")
          .startGraceSeconds(90)
          .startGraceSeverity("WARN")
          .completionDeadlineLocalTime(null)
          .completionDeadlineDayOffset(0)
          .dependencyCompletionWindowSeconds(1200)
          .completionDeadlineSeverity("WARN")
          .updatedBy("operator")
          .build());
      assertThat(readCompletionBaseline()).isAfter(clockBaseline);
      assertThat(jdbcTemplate.queryForObject(
              "select dependency_completion_window_seconds from batch.job_monitoring_policy",
              Integer.class))
          .isEqualTo(1200);
    }
  }

  private static OffsetDateTime readCompletionBaseline() {
    return jdbcTemplate.queryForObject(
        "select completion_deadline_updated_at from batch.job_monitoring_policy",
        OffsetDateTime.class);
  }

  private static void parseMapper(
      org.apache.ibatis.session.Configuration configuration, String resource) throws Exception {
    try (var input = Resources.getResourceAsStream(resource)) {
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
    }
  }
}
