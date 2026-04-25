package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.domain.TriggerRegistrationService;
import java.time.LocalTime;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QuartzJdbcClusterIntegrationTest extends AbstractIntegrationTest {

  @Autowired private Scheduler scheduler;

  @Autowired private TriggerRegistrationService triggerRegistrationService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private DataSource dataSource;

  @Test
  void shouldPersistQuartzJobAndTriggerIntoJdbcStore() throws Exception {
    String tenantId = "t-quartz-" + System.nanoTime();
    String jobCode = "JOB_QUARTZ_" + System.nanoTime();
    String calendarCode = "CAL_QUARTZ_" + System.nanoTime();
    String jobKeyName = tenantId + ":" + jobCode;
    String cronExpr = "0 0 0 1 1 ? 2099";

    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, ?)",
        tenantId,
        tenantId,
        "ACTIVE");
    insertBusinessCalendar(tenantId, calendarCode);
    insertCronJobDefinition(tenantId, jobCode, calendarCode, cronExpr);

    try {
      triggerRegistrationService.registerAll();

      JobKey jobKey = JobKey.jobKey(jobKeyName, "batch-trigger");
      assertThat(scheduler.checkExists(jobKey)).isTrue();
      assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence()).isTrue();
      assertThat(scheduler.getMetaData().isJobStoreClustered()).isTrue();

      Integer jobDetailCount =
          jdbcTemplate.queryForObject(
              """
              select count(*)::int
              from quartz.QRTZ_JOB_DETAILS
              where JOB_NAME = ? and JOB_GROUP = ?
              """,
              Integer.class,
              jobKeyName,
              "batch-trigger");
      Integer triggerCount =
          jdbcTemplate.queryForObject(
              """
              select count(*)::int
              from quartz.QRTZ_CRON_TRIGGERS
              where TRIGGER_NAME = ? and TRIGGER_GROUP = ?
              """,
              Integer.class,
              jobKeyName,
              "batch-trigger");

      assertThat(jobDetailCount).isEqualTo(1);
      assertThat(triggerCount).isEqualTo(1);
    } finally {
      try {
        scheduler.deleteJob(JobKey.jobKey(jobKeyName, "batch-trigger"));
      } catch (Exception ignored) {
        // cleanup best-effort
      }
      jdbcTemplate.update(
          "delete from batch.job_definition where tenant_id = ? and job_code = ?",
          tenantId,
          jobCode);
      jdbcTemplate.update(
          "delete from batch.business_calendar where tenant_id = ? and calendar_code = ?",
          tenantId,
          calendarCode);
      jdbcTemplate.update("delete from batch.tenant where tenant_id = ?", tenantId);
    }
  }

  @Test
  void shouldShowClusterMembersWhenASecondQuartzNodeStarts() throws Exception {
    SchedulerFactoryBean secondaryFactory = new SchedulerFactoryBean();
    secondaryFactory.setSchedulerName(scheduler.getSchedulerName());
    secondaryFactory.setDataSource(dataSource);
    secondaryFactory.setAutoStartup(false);
    secondaryFactory.setWaitForJobsToCompleteOnShutdown(true);
    secondaryFactory.setQuartzProperties(clusterProperties("trigger-node-2"));
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

  private void insertBusinessCalendar(String tenantId, String calendarCode) {
    jdbcTemplate.update(
        """
        insert into batch.business_calendar (
            tenant_id, calendar_code, calendar_name, timezone,
            holiday_roll_rule, catch_up_policy, catch_up_max_days,
            cutoff_time, late_arrival_tolerance_min, sla_offset_min, enabled
        ) values (?, ?, ?, ?, 'SKIP', 'AUTO', 3, ?, 30, 120, true)
        """,
        tenantId,
        calendarCode,
        calendarCode,
        "UTC",
        LocalTime.of(0, 0));
  }

  private void insertCronJobDefinition(
      String tenantId, String jobCode, String calendarCode, String cronExpr) {
    jdbcTemplate.update(
        """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type,
            schedule_expr, timezone, priority, queue_code, worker_group,
            calendar_code, trigger_mode, dag_enabled, shard_strategy, retry_policy,
            retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, 'GENERAL', 'IT', 'CRON', ?, 'UTC', 5, 'q-quartz', ?,
            ?, 'SCHEDULED', false, 'NONE', 'NONE', 0, 0, true, 1)
        """,
        tenantId,
        jobCode,
        jobCode,
        cronExpr,
        jobCode,
        calendarCode);
  }

  private Properties clusterProperties(String instanceId) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("org.quartz.scheduler.instanceName", scheduler.getSchedulerName());
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
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*)::int from quartz.QRTZ_SCHEDULER_STATE where SCHED_NAME = ?",
            Integer.class,
            schedulerName);
    return count == null ? 0 : count;
  }
}
