package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.observability.application.progress.DailyProgressResponse.StatusCount;
import io.github.pinpols.batch.console.domain.observability.mapper.DailyProgressMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
@DisplayName("租户业务日进度五层统计与计划覆盖水位")
class DailyProgressMapperIntegrationTest extends AbstractIntegrationTest {
  private final JdbcTemplate jdbc;
  private final DailyProgressMapper mapper;
  private final SqlSessionTemplate session;

  @Autowired
  DailyProgressMapperIntegrationTest(
      JdbcTemplate jdbc, DailyProgressMapper mapper, SqlSessionTemplate session) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.session = session;
  }

  @Test
  @DisplayName("计划与实际运行不混算，排除演练和替代计划，并按租户业务日隔离")
  void shouldSeparatePlannedAndActual_whenRunsAndSchedulesOverlap() {
    String tenant = "daily-" + UUID.randomUUID();
    LocalDate day = LocalDate.of(2040, 4, 5);
    mapper.setTenantContext(tenant);
    Long workflow = insertWorkflow(tenant);
    insertRun(tenant, workflow, day, "SUCCESS", false);
    insertRun(tenant, workflow, day, "FAILED", false);
    insertRun(tenant, workflow, day, "SUCCESS", true);
    insertRun(tenant, workflow, day.minusDays(1), "SUCCESS", false);
    Long otherWorkflow = insertWorkflow(tenant + "-b");
    insertRun(tenant + "-b", otherWorkflow, day, "SUCCESS", false);
    String insertOccurrenceSql = """
      insert into batch.scheduled_occurrence
      (tenant_id,workflow_definition_id,workflow_code,schedule_version,calendar_code,scheduled_fire_time,biz_date,occurrence_status)
      values (?,?,'DAILY',1,'CAL',?::timestamptz,?,?)
      """;
    jdbc.update(insertOccurrenceSql, tenant, workflow, "2040-04-05T01:00:00Z", day, "PLANNED");
    jdbc.update(insertOccurrenceSql, tenant, workflow, "2040-04-05T02:00:00Z", day, "PLANNED");
    jdbc.update(
        insertOccurrenceSql, tenant, workflow, "2040-04-05T03:00:00Z", day, "MISFIRE_SKIPPED");
    jdbc.update(insertOccurrenceSql, tenant, workflow, "2040-04-05T04:00:00Z", day, "SUPERSEDED");
    assertThat(mapper.selectCounts(tenant, day))
        .containsExactlyInAnyOrder(
            new StatusCount("SCHEDULED_OCCURRENCE", "PLANNED", 2L),
            new StatusCount("SCHEDULED_OCCURRENCE", "MISFIRE_SKIPPED", 1L),
            new StatusCount("WORKFLOW_RUN", "SUCCESS", 1L),
            new StatusCount("WORKFLOW_RUN", "FAILED", 1L));
    assertThat(mapper.selectCounts("absent-tenant", day)).isEmpty();
    assertThat(mapper.selectCoverage(tenant, day).uncoveredDefinitions()).isEqualTo(1);
    String insertDaySql = """
      insert into batch.batch_day_instance(tenant_id,calendar_code,biz_date,day_status,cutoff_at)
      values (?,'CAL',?,'OPEN','2040-04-05T23:59:00Z')
      """;
    jdbc.update(insertDaySql, tenant, day);
    String insertCoverageSql = """
      insert into batch.scheduled_occurrence_coverage
      (tenant_id,workflow_definition_id,schedule_version,calendar_code,coverage_from_at,coverage_through_at,last_success_at)
      values (?,?,1,'CAL','2040-04-05T00:00:00Z','2040-04-06T00:00:00Z','2040-04-06T00:00:00Z')
      """;
    jdbc.update(insertCoverageSql, tenant, workflow);
    session.clearCache();
    assertThat(mapper.selectCoverage(tenant, day).scheduledDefinitions()).isEqualTo(1);
    assertThat(mapper.selectCoverage(tenant, day).uncoveredDefinitions()).isZero();
    String updateVersionSql = "update batch.workflow_definition set version=2 where id=?";
    jdbc.update(updateVersionSql, workflow);
    session.clearCache();
    assertThat(mapper.selectCoverage(tenant, day).uncoveredDefinitions()).isEqualTo(1);
  }

  @Test
  @DisplayName("作业、任务与根触发请求各用独立分母，演练任务不污染真实任务量")
  void shouldCountJobsTasksAndRootRequests_whenChildTriggersExist() {
    String tenant = "daily-task-" + UUID.randomUUID();
    LocalDate day = LocalDate.of(2040, 4, 5);
    mapper.setTenantContext(tenant);
    Long workflow = insertWorkflow(tenant);
    String rootSql = """
      insert into batch.trigger_request
      (tenant_id,request_id,workflow_definition_id,biz_date,trigger_type,dedup_key,request_status)
      values (?,'ROOT',?,?,'MANUAL','ROOT','LAUNCHED') returning id
      """;
    Long rootRequest = jdbc.queryForObject(rootSql, Long.class, tenant, workflow, day);
    String runSql = """
      insert into batch.workflow_run(tenant_id,workflow_definition_id,trigger_request_id,biz_date,run_status)
      values (?,?,?,?,'RUNNING') returning id
      """;
    Long run = jdbc.queryForObject(runSql, Long.class, tenant, workflow, rootRequest, day);
    String definitionSql = """
      insert into batch.job_definition(tenant_id,job_code,job_name,job_type,schedule_type,timezone)
      values (?,'DAILY_JOB','Daily job','GENERAL','MANUAL','UTC') returning id
      """;
    Long jobDefinition = jdbc.queryForObject(definitionSql, Long.class, tenant);
    String childSql = """
      insert into batch.trigger_request
      (tenant_id,request_id,job_code,biz_date,trigger_type,dedup_key,request_status,parent_workflow_run_id,parent_node_code)
      values (?,'CHILD','DAILY_JOB',?,'MANUAL','CHILD','LAUNCHED',?,'A')
      """;
    jdbc.update(childSql, tenant, day, run);
    String jobSql = """
      insert into batch.job_instance
      (tenant_id,job_definition_id,job_code,instance_no,biz_date,trigger_type,instance_status,dedup_key,dry_run)
      values (?,?,'DAILY_JOB',?,?,'MANUAL','SUCCESS',?,?) returning id
      """;
    Long job =
        jdbc.queryForObject(jobSql, Long.class, tenant, jobDefinition, "REAL", day, "REAL", false);
    Long dryJob =
        jdbc.queryForObject(jobSql, Long.class, tenant, jobDefinition, "DRY", day, "DRY", true);
    String taskSql = """
      insert into batch.job_task(tenant_id,job_instance_id,job_instance_biz_date,task_seq,task_status,dry_run)
      values (?,?,?,?,?,?)
      """;
    jdbc.update(taskSql, tenant, job, day, 1, "SUCCESS", false);
    jdbc.update(taskSql, tenant, job, day, 2, "FAILED", false);
    jdbc.update(taskSql, tenant, job, day, 3, "SUCCESS", true);
    jdbc.update(taskSql, tenant, dryJob, day, 1, "SUCCESS", true);
    assertThat(mapper.selectCounts(tenant, day))
        .containsExactlyInAnyOrder(
            new StatusCount("TRIGGER_REQUEST", "LAUNCHED", 1L),
            new StatusCount("WORKFLOW_RUN", "RUNNING", 1L),
            new StatusCount("JOB_INSTANCE", "SUCCESS", 1L),
            new StatusCount("JOB_TASK", "SUCCESS", 1L),
            new StatusCount("JOB_TASK", "FAILED", 1L));
  }

  private Long insertWorkflow(String tenant) {
    String sql = """
      insert into batch.workflow_definition
      (tenant_id,workflow_code,workflow_name,workflow_type,version,enabled,schedule_enabled,schedule_type,schedule_expr,trigger_mode,calendar_code)
      values (?,'DAILY','Daily','DAG',1,true,true,'CRON','0 0 * * * ?','SCHEDULED','CAL') returning id
      """;
    return jdbc.queryForObject(sql, Long.class, tenant);
  }

  private void insertRun(
      String tenant, Long workflow, LocalDate day, String status, boolean dryRun) {
    String sql = """
      insert into batch.workflow_run(tenant_id,workflow_definition_id,biz_date,run_status,dry_run)
      values (?,?,?,?,?)
      """;
    jdbc.update(sql, tenant, workflow, day, status, dryRun);
  }
}
