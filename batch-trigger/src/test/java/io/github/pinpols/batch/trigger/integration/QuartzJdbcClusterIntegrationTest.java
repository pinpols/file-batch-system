package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.infrastructure.QuartzLaunchJob;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "batch.kafka.namespace=quartz-isolation-test")
@DisplayName("Quartz JDBC 集群:注册的 Job 与 Trigger 落 QRTZ 表,且多实例互相可见")
class QuartzJdbcClusterIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private Scheduler scheduler;

  @Autowired
  private TriggerRegistrationService triggerRegistrationService;

  @Autowired
  private TriggerDefinitionLoader triggerDefinitionLoader;

  @Autowired
  private TriggerService triggerService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private DataSource dataSource;

  @Test
  @DisplayName("注册的 cron 任务在 QRTZ 表各落一条 JobDetail 与 CronTrigger,且 JobStore 具备持久化与集群能力")
  void shouldPersistQuartzJobAndTriggerIntoJdbcStore() throws Exception {
    assertThat(scheduler.getSchedulerName())
        .isEqualTo("BatchTriggerScheduler-quartz-isolation-test");
    final String insertTenantSql = """
      insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, ?)
  """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
              single_job_definition_id, schedule_type, schedule_expr, timezone, trigger_mode,
              schedule_enabled, calendar_code
          )
          select ?, ?, ?, 'DAG', 1, true, id, 'CRON', ?, 'UTC', 'SCHEDULED', true, ?
            from batch.job_definition where tenant_id = ? and job_code = ?
          """;
    final String selectCountSql = """
                select count(*)::int
                from quartz.QRTZ_JOB_DETAILS
                where JOB_NAME = ? and JOB_GROUP = ?
                """;
    final String selectCountSql2 = """
              select count(*)::int
              from quartz.QRTZ_CRON_TRIGGERS
              where TRIGGER_NAME = ? and TRIGGER_GROUP = ?
              """;
    final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition set version=2, schedule_expr=? where tenant_id=? and workflow_code=?\
      """;
    final String selectCountSql3 = """
          select count(*)::int
          from quartz.QRTZ_TRIGGERS
          where JOB_NAME = ? and JOB_GROUP = ?
          """;
    final String selectCountSql4 = """
      select count(*) from batch.trigger_request where tenant_id=? and request_id=?
  """;
    final String selectCountSql5 = """
      select count(*) from batch.trigger_outbox_event where tenant_id=? and request_id=?
  """;
    final String deleteTriggerOutboxEventSql = """
        delete from batch.trigger_outbox_event where tenant_id=? and request_id=?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id=? and request_id=?
    """;
    final String deleteScheduledOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id=? and workflow_definition_id=(select id\s\
        from batch.workflow_definition where tenant_id=? and workflow_code=?) and scheduled_fire_time=?\
        """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ? and job_code = ?
    """;
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id = ? and calendar_code = ?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = ? and calendar_code = ?
    """;
    final String deleteTenantSql = """
        delete from batch.tenant where tenant_id = ?
    """;

    String tenantId = "t-quartz-" + System.nanoTime();
    String jobCode = "JOB_QUARTZ_" + System.nanoTime();
    String calendarCode = "CAL_QUARTZ_" + System.nanoTime();
    String workflowCode = "WF_" + jobCode;
    String jobKeyName = tenantId + ":WF:" + workflowCode;
    String cronExpr = "0 0 0 1 1 ? 2099";

    jdbcTemplate.update(insertTenantSql, tenantId, tenantId, "ACTIVE");
    insertBusinessCalendar(tenantId, calendarCode);
    insertCronJobDefinition(tenantId, jobCode, calendarCode, cronExpr);
    jdbcTemplate.update(
        insertWorkflowDefinitionSql,
        tenantId,
        workflowCode,
        workflowCode,
        cronExpr,
        calendarCode,
        tenantId,
        jobCode);

    try {
      TriggerDescriptor staleDescriptor =
          triggerDefinitionLoader.loadByWorkflowCode(tenantId, workflowCode);
      triggerRegistrationService.registerAll();

      JobKey jobKey = JobKey.jobKey(jobKeyName, "batch-trigger");
      assertThat(scheduler.checkExists(jobKey)).isTrue();
      assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence()).isTrue();
      assertThat(scheduler.getMetaData().isJobStoreClustered()).isTrue();

      Integer jobDetailCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, jobKeyName, "batch-trigger");
      Integer triggerCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, jobKeyName, "batch-trigger");

      assertThat(jobDetailCount).isEqualTo(1);
      assertThat(triggerCount).isEqualTo(1);

      String updatedCronExpr = "0 30 1 1 2 ? 2099";
      jdbcTemplate.update(updateWorkflowDefinitionSql, updatedCronExpr, tenantId, workflowCode);
      triggerRegistrationService.reconcileByWorkflowCode(tenantId, workflowCode);

      var updatedJob = scheduler.getJobDetail(jobKey);
      var updatedTrigger =
          (CronTrigger) scheduler.getTrigger(TriggerKey.triggerKey(jobKeyName, "batch-trigger"));
      assertThat(updatedJob.getJobDataMap().getInt(QuartzLaunchJob.WORKFLOW_VERSION))
          .isEqualTo(2);
      assertThat(updatedJob.getJobDataMap().getString(QuartzLaunchJob.SCHEDULE_EXPRESSION))
          .isEqualTo(updatedCronExpr);
      assertThat(updatedTrigger.getCronExpression()).isEqualTo(updatedCronExpr);
      Integer selectedCount =
          jdbcTemplate.queryForObject(selectCountSql3, Integer.class, jobKeyName, "batch-trigger");
      assertThat(selectedCount).isEqualTo(1);

      Instant oldScheduleFire = Instant.parse("2099-01-01T00:00:00Z");
      var staleResponse = triggerService.launchScheduled(new ScheduledTriggerCommand(
          staleDescriptor,
          oldScheduleFire,
          TriggerType.SCHEDULED,
          "stale-quartz-version-" + workflowCode,
          "stale-quartz-trace-" + workflowCode));
      assertThat(staleResponse.instanceNo()).isNull();
      Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
          selectCountSql4, Integer.class, tenantId, "stale-quartz-version-" + workflowCode);
      assertThat(selectedTriggerRequestCount).isZero();
      Integer selectedTriggerOutboxEventCount = jdbcTemplate.queryForObject(
          selectCountSql5, Integer.class, tenantId, "stale-quartz-version-" + workflowCode);
      assertThat(selectedTriggerOutboxEventCount).isZero();
    } finally {
      try {
        scheduler.deleteJob(JobKey.jobKey(jobKeyName, "batch-trigger"));
      } catch (Exception ignored) {
        // 尽力清理
      }
      jdbcTemplate.update(
          deleteTriggerOutboxEventSql, tenantId, "stale-quartz-version-" + workflowCode);
      jdbcTemplate.update(
          deleteTriggerRequestSql, tenantId, "stale-quartz-version-" + workflowCode);
      jdbcTemplate.update(
          deleteScheduledOccurrenceSql,
          tenantId,
          tenantId,
          workflowCode,
          Timestamp.from(Instant.parse("2099-01-01T00:00:00Z")));
      jdbcTemplate.update(deleteWorkflowDefinitionSql, tenantId, workflowCode);
      jdbcTemplate.update(deleteJobDefinitionSql, tenantId, jobCode);
      jdbcTemplate.update(deleteBatchDayCatchupControlSql, tenantId, calendarCode);
      jdbcTemplate.update(deleteBusinessCalendarSql, tenantId, calendarCode);
      jdbcTemplate.update(deleteTenantSql, tenantId);
    }
  }

  @Test
  @DisplayName("第二个 Quartz 节点启动后 QRTZ_SCHEDULER_STATE 中可见至少两个集群实例")
  void shouldShowClusterMembersWhenASecondQuartzNodeStarts() throws Exception {
    SchedulerFactoryBean secondaryFactory = new SchedulerFactoryBean();
    secondaryFactory.setSchedulerName(scheduler.getSchedulerName());
    secondaryFactory.setDataSource(dataSource);
    secondaryFactory.setAutoStartup(false);
    secondaryFactory.setWaitForJobsToCompleteOnShutdown(true);
    secondaryFactory.setQuartzProperties(
        clusterProperties(scheduler.getSchedulerName(), "trigger-node-2"));
    secondaryFactory.afterPropertiesSet();

    Scheduler secondaryScheduler = secondaryFactory.getScheduler();
    try {
      secondaryScheduler.start();

      int observed = waitForSchedulerStateRows(scheduler.getSchedulerName(), 2);
      assertThat(observed).isGreaterThanOrEqualTo(2);
    } finally {
      secondaryScheduler.shutdown(true);
    }
  }

  @Test
  @DisplayName("共享 Quartz 表时不同部署 namespace 的 Job 不可见")
  void shouldKeepQuartzJobsInvisibleAcrossDeploymentNamespaces() throws Exception {
    String secondarySchedulerName = "BatchTriggerScheduler-other-deployment";
    String jobName = "namespace-isolation-" + System.nanoTime();
    String jobGroup = "batch-trigger";
    JobKey jobKey = JobKey.jobKey(jobName, jobGroup);
    SchedulerFactoryBean secondaryFactory = new SchedulerFactoryBean();
    secondaryFactory.setSchedulerName(secondarySchedulerName);
    secondaryFactory.setDataSource(dataSource);
    secondaryFactory.setAutoStartup(false);
    secondaryFactory.setWaitForJobsToCompleteOnShutdown(true);
    secondaryFactory.setQuartzProperties(
        clusterProperties(secondarySchedulerName, "other-deployment-node"));
    secondaryFactory.afterPropertiesSet();

    Scheduler secondaryScheduler = secondaryFactory.getScheduler();
    try {
      secondaryScheduler.start();
      scheduler.addJob(
          JobBuilder.newJob(QuartzLaunchJob.class)
              .withIdentity(jobKey)
              .storeDurably(true)
              .build(),
          true);

      assertThat(scheduler.checkExists(jobKey)).isTrue();
      assertThat(secondaryScheduler.checkExists(jobKey)).isFalse();
      String selectJobCountSql = """
          select count(*)::int
          from quartz.QRTZ_JOB_DETAILS
          where SCHED_NAME = ? and JOB_NAME = ? and JOB_GROUP = ?
          """;
      Integer primaryJobCount = jdbcTemplate.queryForObject(
          selectJobCountSql, Integer.class, scheduler.getSchedulerName(), jobName, jobGroup);
      Integer secondaryJobCount = jdbcTemplate.queryForObject(
          selectJobCountSql, Integer.class, secondarySchedulerName, jobName, jobGroup);

      assertThat(primaryJobCount).isEqualTo(1);
      assertThat(secondaryJobCount).isZero();
    } finally {
      scheduler.deleteJob(jobKey);
      secondaryScheduler.shutdown(true);
    }
  }

  private void insertBusinessCalendar(String tenantId, String calendarCode) {
    final String insertBusinessCalendarSql = """
          insert into batch.business_calendar (
              tenant_id, calendar_code, calendar_name, timezone,
              holiday_roll_rule, catch_up_policy, catch_up_max_days,
              cutoff_time, late_arrival_tolerance_min, sla_offset_min, enabled
          ) values (?, ?, ?, ?, 'SKIP', 'AUTO', 3, ?, 30, 120, true)
          """;

    jdbcTemplate.update(
        insertBusinessCalendarSql,
        tenantId,
        calendarCode.toLowerCase(Locale.ROOT),
        calendarCode,
        "UTC",
        LocalTime.of(0, 0));
  }

  private void insertCronJobDefinition(
      String tenantId, String jobCode, String calendarCode, String cronExpr) {
    final String insertJobDefinitionSql = """
    insert into batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type,
    schedule_expr, timezone, priority, queue_code, worker_group,
    calendar_code, trigger_mode, dag_enabled, shard_strategy, retry_policy,
    retry_max_count, timeout_seconds, enabled, version
    ) values (?, ?, ?, 'GENERAL', 'IT', 'CRON', ?, 'UTC', 5, 'q-quartz', ?,
    ?, 'SCHEDULED', false, 'NONE', 'NONE', 0, 0, true, 1)
    """;

    jdbcTemplate.update(
        insertJobDefinitionSql, tenantId, jobCode, jobCode, cronExpr, jobCode, calendarCode);
  }

  private Properties clusterProperties(String schedulerName, String instanceId) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("org.quartz.scheduler.instanceName", schedulerName);
    properties.setProperty("org.quartz.scheduler.instanceId", instanceId);
    properties.setProperty("org.quartz.jobStore.isClustered", "true");
    properties.setProperty("org.quartz.jobStore.tablePrefix", "quartz.QRTZ_");
    properties.setProperty(
        "org.quartz.jobStore.driverDelegateClass",
        "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
    properties.setProperty("org.quartz.threadPool.threadCount", "1");
    return properties;
  }

  private int waitForSchedulerStateRows(String schedulerName, int expected)
      throws InterruptedException {
    // Quartz clusterCheckinInterval 默认 7.5s；之前 20×250ms=5s 等不到 secondary 注册导致 flaky
    int observed = 0;
    for (int i = 0; i < 60; i++) {
      observed = querySchedulerStateRows(schedulerName);
      if (observed >= expected) {
        return observed;
      }
      Thread.sleep(500L);
    }
    return observed;
  }

  private int querySchedulerStateRows(String schedulerName) {
    final String selectCountSql = """
        select count(*)::int from quartz.QRTZ_SCHEDULER_STATE where SCHED_NAME = ?
    """;

    Integer count = jdbcTemplate.queryForObject(selectCountSql, Integer.class, schedulerName);
    return count == null ? 0 : count;
  }
}
