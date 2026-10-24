package io.github.pinpols.batch.console.domain.job.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;
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
    String createBatchSchemaSql = """
        create schema batch
    """;
    jdbcTemplate.execute(createBatchSchemaSql);
    String createJobDefinitionTableSql = """
        create table batch.job_definition (id bigint primary key)
    """;
    jdbcTemplate.execute(createJobDefinitionTableSql);
    String extendJobDefinitionTableSql = """
        alter table batch.job_definition
          add column tenant_id varchar(64), add column job_code varchar(128),
          add column job_name varchar(256), add column depends_on_job_code varchar(128),
          add column trigger_mode varchar(32), add column queue_code varchar(128),
          add column worker_group varchar(128), add column schedule_expr varchar(256),
          add column calendar_code varchar(128), add column window_code varchar(128),
          add column retry_policy varchar(256), add column retry_max_count integer,
          add column timeout_seconds integer, add column shard_strategy varchar(64),
          add column execution_mode varchar(32), add column watermark_field varchar(128),
          add column execution_handler varchar(256), add column param_schema text,
          add column priority integer, add column enabled boolean, add column description text,
          add column version integer not null default 1, add column updated_by varchar(64),
          add column updated_at timestamptz not null default current_timestamp
        """;
    jdbcTemplate.execute(extendJobDefinitionTableSql);
    String createMonitoringPolicyTableSql = """
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
        """;
    jdbcTemplate.execute(createMonitoringPolicyTableSql);
    String insertJobDefinitionSql = """
        insert into batch.job_definition(id) values (1)
    """;
    jdbcTemplate.update(insertJobDefinitionSql);

    Configuration configuration = new Configuration(
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
      String backdateCompletionDeadlineSql = """
          update batch.job_monitoring_policy
             set completion_deadline_updated_at = current_timestamp - interval '1 hour'
          """;
      jdbcTemplate.update(backdateCompletionDeadlineSql);
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
      String selectDependencyCompletionWindowSql = """
          select dependency_completion_window_seconds from batch.job_monitoring_policy
      """;
      Integer selectedDependencyCompletionWindowSeconds =
          jdbcTemplate.queryForObject(selectDependencyCompletionWindowSql, Integer.class);
      assertThat(selectedDependencyCompletionWindowSeconds).isEqualTo(1200);
    }
  }

  @DisplayName("作业维护更新 SQL 持久化执行处理器、参数 schema 与优先级")
  @Test
  void shouldPersistEditableExecutionFields() {
    String insertEditableJobDefinitionSql = """
        insert into batch.job_definition (
          id, tenant_id, job_code, execution_handler, param_schema, priority
        ) values (2, 'tenant-a', 'JOB_EDIT', 'old-handler', '{"type":"string"}', 3)
        """;
    jdbcTemplate.update(insertEditableJobDefinitionSql);
    JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
    param.setTenantId("tenant-a");
    param.setJobCode("JOB_EDIT");
    param.setJobName("job edit");
    param.setTriggerMode("MANUAL");
    param.setExecutionHandler("new-handler");
    param.setParamSchema("{\"type\":\"object\"}");
    param.setPriority(7);
    param.setEnabled(true);
    param.setUpdatedBy("tester");

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobDefinitionMapper mapper = session.getMapper(JobDefinitionMapper.class);
      assertThat(mapper.updateJobDefinitionMaintenance(param)).isEqualTo(1);
    }

    String selectEditableJobDefinitionSql = """
        select execution_handler, param_schema, priority from batch.job_definition where id = 2
    """;
    Map<String, Object> persisted = jdbcTemplate.queryForMap(selectEditableJobDefinitionSql);
    assertThat(persisted)
        .containsEntry("execution_handler", "new-handler")
        .containsEntry("priority", 7);
    Map<String, Object> schema = JsonUtils.fromJson(
        persisted.get("param_schema").toString(), new TypeReference<Map<String, Object>>() {});
    assertThat(schema).isEqualTo(Map.of("type", "object"));
  }

  private static OffsetDateTime readCompletionBaseline() {
    String selectCompletionDeadlineBaselineSql = """
        select completion_deadline_updated_at from batch.job_monitoring_policy
    """;
    return jdbcTemplate.queryForObject(selectCompletionDeadlineBaselineSql, OffsetDateTime.class);
  }

  private static void parseMapper(Configuration configuration, String resource) throws Exception {
    try (var input = Resources.getResourceAsStream(resource)) {
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
    }
  }
}
