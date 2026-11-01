package io.github.pinpols.batch.console.domain.job.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.job.query.JobDefinitionQuery;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
import io.github.pinpols.batch.console.domain.job.view.JobDependencyRuleView;
import io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageProjectionTestSupport;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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
          add column worker_group varchar(128), add column schedule_type varchar(32), add column schedule_expr varchar(256),
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
    String createLookupTablesSql = """
        CREATE TABLE batch.workflow_definition (
          id BIGINT PRIMARY KEY, tenant_id VARCHAR(64), single_job_definition_id BIGINT);
        CREATE TABLE batch.job_dependency (
          tenant_id VARCHAR(64), downstream_job_code VARCHAR(128), upstream_job_code VARCHAR(128),
          dependency_order INTEGER, biz_date_alignment VARCHAR(32))
        """;
    jdbcTemplate.execute(createLookupTablesSql);
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
    ClassPathResource migration =
        new ClassPathResource("db/migration/V355__job_monitoring_rule_effective_times.sql");
    new ResourceDatabasePopulator(migration).execute(dataSource);
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

  @Test
  @DisplayName("作业聚合查询保留关联 Workflow、依赖规则和监控策略并隔离租户")
  void shouldMapJobDefinitionAggregateWithinTenant() {
    String fixtures = """
        INSERT INTO batch.job_definition(id, tenant_id, job_code, job_name, schedule_type)
        VALUES (98, 'tenant-projection', 'JOB_DOWN', 'Downstream', 'MANUAL'),
               (99, 'tenant-projection', 'JOB_LEGACY', 'Legacy', 'MANUAL');
        UPDATE batch.job_definition SET depends_on_job_code = 'LEGACY_UP' WHERE id = 99;
        INSERT INTO batch.workflow_definition(id, tenant_id, single_job_definition_id)
        VALUES (998, 'tenant-projection', 98);
        INSERT INTO batch.job_dependency
          (tenant_id, downstream_job_code, upstream_job_code, dependency_order, biz_date_alignment)
        VALUES ('tenant-projection', 'JOB_DOWN', 'JOB_A', 1, 'SAME_DAY'),
               ('tenant-projection', 'JOB_DOWN', 'JOB_B', 2, 'PREVIOUS_BIZ_DAY');
        """;
    jdbcTemplate.execute(fixtures);
    JobMonitoringPolicyUpsertParam policy = JobMonitoringPolicyUpsertParam.builder()
        .tenantId("tenant-projection")
        .jobDefinitionId(98L)
        .softRuntimeSeconds(120)
        .softRuntimeSeverity("ERROR")
        .startGraceSeconds(45)
        .startGraceSeverity("WARN")
        .completionDeadlineLocalTime(null)
        .completionDeadlineDayOffset(0)
        .dependencyCompletionWindowSeconds(90)
        .completionDeadlineSeverity("WARN")
        .updatedBy("operator")
        .build();
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobDefinitionMapper mapper = session.getMapper(JobDefinitionMapper.class);
      mapper.upsertJobMonitoringPolicy(policy);
      JobDefinitionView row = mapper.selectById("tenant-projection", 98L);
      assertThat(row.getDefaultWorkflowDefinitionId()).isEqualTo(998L);
      assertThat(row.getDependsOnJobCodes()).containsExactly("JOB_A", "JOB_B");
      assertThat(row.getJobDependencyRules()).containsExactly(
          new JobDependencyRuleView("JOB_A", "SAME_DAY"),
          new JobDependencyRuleView("JOB_B", "PREVIOUS_BIZ_DAY"));
      Map<String, Object> exported = TenantConfigPackageProjectionTestSupport.jobRow(row);
      assertThat(exported.get("depends_on_job_codes")).isEqualTo("JOB_A,JOB_B");
      assertThat(exported.get("depends_on_biz_date_alignments"))
          .isEqualTo("SAME_DAY,PREVIOUS_BIZ_DAY");
      JobDefinitionView legacy = mapper.selectById("tenant-projection", 99L);
      Map<String, Object> legacyExport = TenantConfigPackageProjectionTestSupport.jobRow(legacy);
      assertThat(legacyExport.get("depends_on_job_codes")).isEqualTo("LEGACY_UP");
      assertThat(legacyExport.get("depends_on_biz_date_alignments")).isEqualTo("SAME_DAY");
      assertThat(row.getSoftRuntimeSeconds()).isEqualTo(120);
      assertThat(row.getSoftRuntimeSeverity()).isEqualTo("ERROR");
      assertThat(row.getStartGraceSeconds()).isEqualTo(45);
      assertThat(row.getDependencyCompletionWindowSeconds()).isEqualTo(90);
      assertThat(mapper.selectByUniqueKey("tenant-projection", "JOB_DOWN")
              .getDefaultWorkflowDefinitionId())
          .isEqualTo(998L);
      assertThat(mapper.selectById("other-tenant", 98L)).isNull();
    }
  }

  @Test
  @DisplayName("运行与启动规则独立生效，严重级别编辑不重置任一规则时钟")
  void shouldKeepRuntimeAndStartActivationTimesIndependent() {
    String insertDefinitionSql = """
        INSERT INTO batch.job_definition(id) VALUES (3)
        """;
    jdbcTemplate.update(insertDefinitionSql);
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobDefinitionMapper mapper = session.getMapper(JobDefinitionMapper.class);
      mapper.upsertJobMonitoringPolicy(clockPolicy(30, 60, "WARN"));
      String backdateSql = """
          UPDATE batch.job_monitoring_policy
          SET soft_runtime_updated_at = CURRENT_TIMESTAMP - INTERVAL '1 hour',
              start_grace_updated_at = CURRENT_TIMESTAMP - INTERVAL '1 hour',
              completion_deadline_updated_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'
          WHERE tenant_id = 'tenant-clock' AND job_definition_id = 3
          """;
      jdbcTemplate.update(backdateSql);
      RuleTimes original = readRuleTimes();
      mapper.upsertJobMonitoringPolicy(clockPolicy(30, 60, "ERROR"));
      RuleTimes severityOnly = readRuleTimes();
      assertThat(severityOnly).isEqualTo(original);
      mapper.upsertJobMonitoringPolicy(clockPolicy(31, 60, "ERROR"));
      RuleTimes runtimeChanged = readRuleTimes();
      assertThat(runtimeChanged.runtime()).isAfter(original.runtime());
      assertThat(runtimeChanged.start()).isEqualTo(original.start());
      assertThat(runtimeChanged.completion()).isEqualTo(original.completion());
      mapper.upsertJobMonitoringPolicy(clockPolicy(31, 61, "ERROR"));
      RuleTimes startChanged = readRuleTimes();
      assertThat(startChanged.runtime()).isEqualTo(runtimeChanged.runtime());
      assertThat(startChanged.start()).isAfter(original.start());
      assertThat(startChanged.completion()).isEqualTo(original.completion());
    }
  }

  private JobMonitoringPolicyUpsertParam clockPolicy(int runtime, int start, String severity) {
    return JobMonitoringPolicyUpsertParam.builder()
        .tenantId("tenant-clock")
        .jobDefinitionId(3L)
        .softRuntimeSeconds(runtime)
        .softRuntimeSeverity(severity)
        .startGraceSeconds(start)
        .startGraceSeverity(severity)
        .completionDeadlineLocalTime(null)
        .completionDeadlineDayOffset(0)
        .dependencyCompletionWindowSeconds(0)
        .completionDeadlineSeverity("WARN")
        .updatedBy("operator")
        .build();
  }

  private RuleTimes readRuleTimes() {
    String selectSql = """
        SELECT soft_runtime_updated_at, start_grace_updated_at, completion_deadline_updated_at
        FROM batch.job_monitoring_policy WHERE tenant_id = 'tenant-clock' AND job_definition_id = 3
        """;
    return jdbcTemplate.queryForObject(
        selectSql,
        (result, row) -> new RuleTimes(
            result.getObject("soft_runtime_updated_at", OffsetDateTime.class),
            result.getObject("start_grace_updated_at", OffsetDateTime.class),
            result.getObject("completion_deadline_updated_at", OffsetDateTime.class)));
  }

  private record RuleTimes(
      OffsetDateTime runtime, OffsetDateTime start, OffsetDateTime completion) {}

  @Test
  @DisplayName("MANUAL 的就绪规则配置在列表和计数中分类一致，完成启用标记不依赖旧定时类型")
  void shouldListConfiguredWorkflowPoliciesAsEligibilityDriven() {
    String insertDefinitionsSql = """
        INSERT INTO batch.job_definition(id, tenant_id, job_code, schedule_type)
        VALUES (10, 'tenant-category', 'CONFIGURED', 'MANUAL'), (11, 'tenant-category', 'RUNTIME_ONLY', 'MANUAL')
        """;
    jdbcTemplate.update(insertDefinitionsSql);
    JobMonitoringPolicyUpsertParam policy = JobMonitoringPolicyUpsertParam.builder()
        .tenantId("tenant-category")
        .jobDefinitionId(10L)
        .softRuntimeSeconds(0)
        .softRuntimeSeverity("WARN")
        .startGraceSeconds(60)
        .startGraceSeverity("WARN")
        .completionDeadlineDayOffset(0)
        .dependencyCompletionWindowSeconds(120)
        .completionDeadlineSeverity("WARN")
        .updatedBy("operator")
        .build();
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobDefinitionMapper mapper = session.getMapper(JobDefinitionMapper.class);
      mapper.upsertJobMonitoringPolicy(policy);
      PageRequest page = new PageRequest(1, 20);
      JobDefinitionQuery eligibility = JobDefinitionQuery.builder()
          .tenantId("tenant-category")
          .monitoringCategory("DEPENDENCY")
          .pageRequest(page)
          .build();
      JobDefinitionQuery runtimeOnly = JobDefinitionQuery.builder()
          .tenantId("tenant-category")
          .monitoringCategory("RUNTIME_ONLY")
          .pageRequest(page)
          .build();
      long eligibilityCount = mapper.countByQuery(eligibility);
      List<JobDefinitionView> configured = mapper.selectByQuery(eligibility);
      long runtimeCount = mapper.countByQuery(runtimeOnly);
      List<JobDefinitionView> runtime = mapper.selectByQuery(runtimeOnly);
      assertThat(eligibilityCount).isOne();
      assertThat(configured)
          .extracting(JobDefinitionView::getJobCode)
          .containsExactly("CONFIGURED");
      assertThat(configured.getFirst().getCompletionDeadlineEnabled()).isTrue();
      assertThat(runtimeCount).isOne();
      assertThat(runtime)
          .extracting(JobDefinitionView::getJobCode)
          .containsExactly("RUNTIME_ONLY");
    }
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
          where tenant_id = 'tenant-a' and job_definition_id = 1
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
          where tenant_id = 'tenant-a' and job_definition_id = 1
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
        where tenant_id = 'tenant-a' and job_definition_id = 1
    """;
    return jdbcTemplate.queryForObject(selectCompletionDeadlineBaselineSql, OffsetDateTime.class);
  }

  private static void parseMapper(Configuration configuration, String resource) throws Exception {
    try (var input = Resources.getResourceAsStream(resource)) {
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
    }
  }
}
