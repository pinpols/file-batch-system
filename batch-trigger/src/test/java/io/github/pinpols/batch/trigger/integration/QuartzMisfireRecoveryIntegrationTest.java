package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.infrastructure.QuartzLaunchJob;
import io.github.pinpols.batch.trigger.infrastructure.QuartzMisfireRecoveryListener;
import io.github.pinpols.batch.trigger.infrastructure.TriggerSchedulerFacade;
import io.github.pinpols.batch.trigger.infrastructure.scheduler.TriggerReconciler;
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;
import org.springframework.test.util.AopTestUtils;

/** Exercises Quartz's JDBC JobStore misfire callback and the durable recovery execution end to end. */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.security.bypass-mode=true",
      "spring.quartz.job-store-type=jdbc",
      "spring.quartz.jdbc.initialize-schema=always",
      "spring.quartz.properties.org.quartz.jobStore.misfireThreshold=1000"
    })
@DisplayName("Quartz JDBC misfire 恢复:真实过期 Workflow Cron trigger 最终写入 CATCH_UP request 与 outbox")
class QuartzMisfireRecoveryIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private Scheduler scheduler;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private ScheduledOccurrenceRecorder occurrenceRecorder;

  @Autowired
  private TriggerReconciler triggerReconciler;

  @Autowired
  private TaskScheduler taskScheduler;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private AutowireCapableBeanFactory beanFactory;

  private String tenantId;
  private String workflowCode;
  private Long workflowDefinitionId;
  private JobKey jobKey;

  @BeforeEach
  void seedWorkflow() {
    tenantId = "quartz-misfire-" + UUID.randomUUID();
    workflowCode = "WF_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')",
        tenantId,
        tenantId);
    jdbcTemplate.update("""
        insert into batch.business_calendar (
            tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
            catch_up_policy, catch_up_max_days, cutoff_time, late_arrival_tolerance_min,
            sla_offset_min, enabled
        ) values (?, 'misfire_cal', 'Misfire calendar', 'UTC', 'SKIP', 'AUTO', 3,
                  ?, 30, 120, true)
        """, tenantId, LocalTime.MIDNIGHT);
    workflowDefinitionId =
        jdbcTemplate.queryForObject("""
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
            schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled, calendar_code
        ) values (?, ?, ?, 'DAG', 1, true, 'CRON', '0 0 * * * ?', 'UTC', 'SCHEDULED', true,
                  'misfire_cal') returning id
        """, Long.class, tenantId, workflowCode, workflowCode);
    jobKey = JobKey.jobKey(tenantId + ":WF:" + workflowCode, TriggerSchedulerFacade.JOB_GROUP);
  }

  @AfterEach
  void cleanUp() throws Exception {
    if (jobKey != null && !scheduler.isShutdown() && scheduler.checkExists(jobKey)) {
      scheduler.deleteJob(jobKey);
    }
    if (tenantId == null) {
      return;
    }
    if (!scheduler.isShutdown()) {
      scheduler.deleteJob(JobKey.jobKey(
          tenantId + ":RECOVERY:" + workflowDefinitionId, TriggerSchedulerFacade.JOB_GROUP));
    }
    jdbcTemplate.update("delete from batch.trigger_outbox_event where tenant_id = ?", tenantId);
    jdbcTemplate.update("delete from batch.trigger_request where tenant_id = ?", tenantId);
    jdbcTemplate.update("delete from batch.scheduled_occurrence where tenant_id = ?", tenantId);
    jdbcTemplate.update(
        "delete from batch.scheduled_occurrence_coverage where tenant_id = ?", tenantId);
    jdbcTemplate.update(
        "delete from batch.workflow_definition where tenant_id = ? and id = ?",
        tenantId,
        workflowDefinitionId);
    jdbcTemplate.update("delete from batch.business_calendar where tenant_id = ?", tenantId);
    jdbcTemplate.update("delete from batch.tenant where tenant_id = ?", tenantId);
  }

  @Test
  @DisplayName("恢复 JDBC 调度器后真实 misfire callback 保留原 fire time 并只产生一个补跑请求")
  void realQuartzMisfire_recoversWorkflowOccurrenceIntoCatchUpRequest() throws Exception {
    Date triggerStartTime = Date.from(Instant.now().minus(Duration.ofHours(3)));
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(workflowJobData())
        .storeDurably()
        .build();
    TriggerKey triggerKey = TriggerKey.triggerKey(jobKey.getName(), jobKey.getGroup());
    CronTrigger trigger = TriggerBuilder.newTrigger()
        .withIdentity(triggerKey)
        .forJob(job)
        .usingJobData(misfireIdentityData())
        .startAt(triggerStartTime)
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?")
            .inTimeZone(TimeZone.getTimeZone("UTC"))
            .withMisfireHandlingInstructionDoNothing())
        .build();

    scheduler.standby();
    if (scheduler.checkExists(jobKey)) {
      scheduler.deleteJob(jobKey);
    }
    assertThat(scheduler.getListenerManager().getTriggerListeners())
        .extracting(org.quartz.TriggerListener::getName)
        .contains("quartzMisfireRecoveryListener");
    // Quartz's misfire thread also runs in standby. Persist the matching obligation before
    // exposing an overdue trigger, otherwise its callback can race ahead of fixture creation.
    Date expectedScheduledFireTime =
        trigger.getFireTimeAfter(Date.from(triggerStartTime.toInstant().minusMillis(1)));
    assertThat(expectedScheduledFireTime)
        .as("the JDBC JobStore must persist an overdue cron fire before scheduler resume")
        .isBefore(Date.from(Instant.now().minusSeconds(60)));
    jdbcTemplate.update(
        """
        insert into batch.scheduled_occurrence (
            tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
            scheduled_fire_time, biz_date, occurrence_status
        ) values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
        """,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        expectedScheduledFireTime,
        expectedScheduledFireTime.toInstant().atZone(ZoneOffset.UTC).toLocalDate());
    scheduler.scheduleJob(job, trigger);
    scheduler.start();

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      Integer requestCount = jdbcTemplate.queryForObject(
          "select count(*) from batch.trigger_request where tenant_id = ?"
              + " and workflow_definition_id = ? and trigger_type = 'CATCH_UP'",
          Integer.class,
          tenantId,
          workflowDefinitionId);
      assertThat(requestCount).isEqualTo(1);
      Integer outboxCount = jdbcTemplate.queryForObject(
          "select count(*) from batch.trigger_outbox_event where tenant_id = ?",
          Integer.class,
          tenantId);
      assertThat(outboxCount).isEqualTo(1);
      LocalDate requestBizDate = jdbcTemplate.queryForObject(
          "select biz_date from batch.trigger_request where tenant_id = ?"
              + " and workflow_definition_id = ? and trigger_type = 'CATCH_UP'",
          LocalDate.class,
          tenantId,
          workflowDefinitionId);
      assertThat(requestBizDate)
          .isEqualTo(
              expectedScheduledFireTime.toInstant().atZone(ZoneOffset.UTC).toLocalDate());
    });

    Integer occurrenceCount = jdbcTemplate.queryForObject(
        "select count(*) from batch.scheduled_occurrence where tenant_id = ?"
            + " and workflow_definition_id = ? and scheduled_fire_time = ?"
            + " and occurrence_status = 'REQUESTED'",
        Integer.class,
        tenantId,
        workflowDefinitionId,
        expectedScheduledFireTime);
    assertThat(occurrenceCount).isEqualTo(1);
  }

  @Test
  @org.springframework.test.annotation.DirtiesContext(
      methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
  @DisplayName("Quartz Scheduler 重启后从 MISFIRE_PENDING 台账重建 one-shot 并接纳一个补跑")
  void restartedQuartzScheduler_restoresPendingRecoveryFromDatabase() throws Exception {
    Instant originalFireTime =
        Instant.now().minus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(workflowJobData())
        .storeDurably()
        .build();
    scheduler.addJob(job, true);
    jdbcTemplate.update(
        """
        insert into batch.scheduled_occurrence (
            tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
            scheduled_fire_time, biz_date, occurrence_status
        ) values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
        """,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        java.sql.Timestamp.from(originalFireTime),
        originalFireTime.atZone(ZoneOffset.UTC).toLocalDate());

    // Simulate a process crash after the callback durably marks the occurrence but before its
    // TaskScheduler handoff can register a Quartz one-shot trigger.
    TaskScheduler lostHandoffScheduler = mock(TaskScheduler.class);
    doAnswer(invocation -> null)
        .when(lostHandoffScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    TriggerKey originalTriggerKey = TriggerKey.triggerKey(jobKey.getName(), jobKey.getGroup());
    Trigger missedTrigger = mock(Trigger.class);
    when(missedTrigger.getKey()).thenReturn(originalTriggerKey);
    when(missedTrigger.getJobKey()).thenReturn(jobKey);
    when(missedTrigger.getJobDataMap()).thenReturn(misfireIdentityData());
    when(missedTrigger.getNextFireTime()).thenReturn(Date.from(originalFireTime));
    QuartzMisfireRecoveryListener interruptedListener = new QuartzMisfireRecoveryListener(
        () -> scheduler, lostHandoffScheduler, occurrenceRecorder);
    interruptedListener.triggerMisfired(missedTrigger);
    assertThat(jdbcTemplate.queryForObject(
            "select occurrence_status from batch.scheduled_occurrence where tenant_id = ? "
                + "and workflow_definition_id = ? and scheduled_fire_time = ?",
            String.class,
            tenantId,
            workflowDefinitionId,
            java.sql.Timestamp.from(originalFireTime)))
        .isEqualTo("MISFIRE_PENDING");

    // Discard the captured in-memory handoff without running it. A new Quartz Scheduler reads the
    // same PostgreSQL JobStore and restores from the durable occurrence ledger.
    scheduler.shutdown(true);
    SchedulerFactoryBean restartedFactory = newSchedulerFactory();
    SchedulerFactoryBean competingFactory = newSchedulerFactory();
    restartedFactory.afterPropertiesSet();
    competingFactory.afterPropertiesSet();
    Scheduler restartedScheduler = restartedFactory.getScheduler();
    Scheduler competingScheduler = competingFactory.getScheduler();
    try {
      assertThat(restartedScheduler.checkExists(jobKey))
          .as("the durable Workflow JobDetail must survive Scheduler shutdown")
          .isTrue();
      QuartzMisfireRecoveryListener restartedRecovery = new QuartzMisfireRecoveryListener(
          () -> restartedScheduler, taskScheduler, occurrenceRecorder);
      QuartzMisfireRecoveryListener competingRecovery = new QuartzMisfireRecoveryListener(
          () -> competingScheduler, taskScheduler, occurrenceRecorder);
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<?> first = pool.submit(() -> {
          ready.countDown();
          if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting for concurrent recovery start");
          }
          restartedRecovery.restorePendingMisfires(tenantId, workflowDefinitionId, workflowCode);
          return null;
        });
        Future<?> second = pool.submit(() -> {
          ready.countDown();
          if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting for concurrent recovery start");
          }
          competingRecovery.restorePendingMisfires(tenantId, workflowDefinitionId, workflowCode);
          return null;
        });
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
      } finally {
        pool.shutdownNow();
      }

      Integer recoveryTriggerCount = jdbcTemplate.queryForObject(
          """
          select count(*)::int
            from quartz.QRTZ_TRIGGERS
           where SCHED_NAME = ? and TRIGGER_GROUP = 'batch-trigger-recovery'
             and TRIGGER_NAME = ?
          """,
          Integer.class,
          scheduler.getSchedulerName(),
          "misfire-recovery-" + workflowDefinitionId + "-" + originalFireTime.toEpochMilli());
      assertThat(recoveryTriggerCount)
          .as("two Quartz nodes racing to restore the same occurrence must persist one trigger")
          .isEqualTo(1);

      restartedScheduler.start();
      competingScheduler.start();

      await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
        Integer requestCount = jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ?"
                + " and workflow_definition_id = ? and trigger_type = 'CATCH_UP'",
            Integer.class,
            tenantId,
            workflowDefinitionId);
        assertThat(requestCount).isEqualTo(1);
        Integer occurrenceCount = jdbcTemplate.queryForObject(
            "select count(*) from batch.scheduled_occurrence where tenant_id = ?"
                + " and workflow_definition_id = ? and scheduled_fire_time = ?"
                + " and occurrence_status = 'REQUESTED'",
            Integer.class,
            tenantId,
            workflowDefinitionId,
            java.sql.Timestamp.from(originalFireTime));
        assertThat(occurrenceCount).isEqualTo(1);
      });
    } finally {
      if (!restartedScheduler.isShutdown() && restartedScheduler.checkExists(jobKey)) {
        restartedScheduler.deleteJob(jobKey);
      }
      if (!competingScheduler.isShutdown()) {
        competingScheduler.shutdown(true);
      }
      competingFactory.destroy();
      restartedFactory.destroy();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Workflow 暂停新调度后既有 misfire 继续恢复,不重建主日程且只接纳一次补跑")
  void pausedWorkflow_recoversExistingMisfireWithoutResumingSchedule(boolean disableWorkflow)
      throws Exception {
    Instant originalFireTime =
        Instant.now().minus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(workflowJobData())
        .storeDurably()
        .build();
    scheduler.addJob(job, true);
    jdbcTemplate.update(
        """
        insert into batch.scheduled_occurrence (
            tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
            scheduled_fire_time, biz_date, occurrence_status
        ) values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
        """,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        java.sql.Timestamp.from(originalFireTime),
        originalFireTime.atZone(ZoneOffset.UTC).toLocalDate());

    TaskScheduler lostHandoffScheduler = mock(TaskScheduler.class);
    doAnswer(invocation -> null)
        .when(lostHandoffScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    Trigger missedTrigger = mock(Trigger.class);
    when(missedTrigger.getKey())
        .thenReturn(TriggerKey.triggerKey(jobKey.getName(), jobKey.getGroup()));
    when(missedTrigger.getJobKey()).thenReturn(jobKey);
    when(missedTrigger.getJobDataMap()).thenReturn(misfireIdentityData());
    when(missedTrigger.getNextFireTime()).thenReturn(Date.from(originalFireTime));
    new QuartzMisfireRecoveryListener(() -> scheduler, lostHandoffScheduler, occurrenceRecorder)
        .triggerMisfired(missedTrigger);

    jdbcTemplate.update(
        "update batch.workflow_definition set schedule_enabled = false, enabled = ? "
            + "where tenant_id = ? and id = ?",
        !disableWorkflow,
        tenantId,
        workflowDefinitionId);
    jdbcTemplate.update(
        "update batch.tenant set status = 'SUSPENDED' where tenant_id = ?", tenantId);
    // Bypass the startup ShedLock lease: this test asserts reconciler behavior, not lock timing.
    AopTestUtils.<TriggerReconciler>getTargetObject(triggerReconciler).reconcile();
    assertThat(scheduler.checkExists(jobKey)).isFalse();
    assertThat(occurrenceStatus(originalFireTime)).isEqualTo("MISFIRE_PENDING");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ?",
            Integer.class,
            tenantId))
        .isZero();

    jdbcTemplate.update("update batch.tenant set status = 'ACTIVE' where tenant_id = ?", tenantId);
    AopTestUtils.<TriggerReconciler>getTargetObject(triggerReconciler).reconcile();

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "select count(*) from batch.trigger_request where tenant_id = ?"
                    + " and workflow_definition_id = ? and trigger_type = 'CATCH_UP'",
                Integer.class,
                tenantId,
                workflowDefinitionId))
            .isEqualTo(1));
    assertThat(occurrenceStatus(originalFireTime)).isEqualTo("REQUESTED");
    assertThat(jdbcTemplate.queryForObject(
            "select schedule_enabled from batch.workflow_definition where tenant_id = ? and id = ?",
            Boolean.class,
            tenantId,
            workflowDefinitionId))
        .isFalse();
    assertThat(jdbcTemplate.queryForObject(
            "select enabled from batch.workflow_definition where tenant_id=? and id=?",
            Boolean.class,
            tenantId,
            workflowDefinitionId))
        .isEqualTo(!disableWorkflow);
    assertThat(scheduler.getTrigger(TriggerKey.triggerKey(jobKey.getName(), jobKey.getGroup())))
        .isNull();
    AopTestUtils.<TriggerReconciler>getTargetObject(triggerReconciler).reconcile();
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ? and trigger_type = 'CATCH_UP'",
            Integer.class,
            tenantId))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("旧定义的待恢复 fire 使用独立恢复目标,不借用同编码新定义的主调度参数")
  void olderPendingMisfire_shouldKeepItsDefinitionWhenNewerPrimaryIsRegistered() throws Exception {
    Instant fire =
        Instant.now().minus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    jdbcTemplate.update(
        "update batch.workflow_definition set schedule_enabled = false where id = ?",
        workflowDefinitionId);
    jdbcTemplate.update(
        """
        insert into batch.scheduled_occurrence (
            tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
            scheduled_fire_time, biz_date, occurrence_status)
        values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
        """,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        java.sql.Timestamp.from(fire),
        fire.atZone(ZoneOffset.UTC).toLocalDate());
    assertThat(occurrenceRecorder.markMisfirePending(tenantId, workflowDefinitionId, fire))
        .isTrue();
    Long newerId = jdbcTemplate.queryForObject("""
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
            schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled, calendar_code)
        values (?, ?, 'Newer primary', 'DAG', 2, true, 'CRON',
            '0 0 0 1 1 ? 2099', 'UTC', 'SCHEDULED', true, 'misfire_cal') returning id
        """, Long.class, tenantId, workflowCode);
    try {
      AopTestUtils.<TriggerReconciler>getTargetObject(triggerReconciler).reconcile();
      await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?",
                Integer.class,
                tenantId,
                workflowDefinitionId))
            .isEqualTo(1);
        assertThat(occurrenceStatus(fire)).isEqualTo("REQUESTED");
      });
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?",
              Integer.class,
              tenantId,
              newerId))
          .isZero();
      assertThat(scheduler
              .getJobDetail(jobKey)
              .getJobDataMap()
              .getLong(QuartzLaunchJob.WORKFLOW_DEFINITION_ID))
          .isEqualTo(newerId);
    } finally {
      jdbcTemplate.update(
          "delete from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?",
          tenantId,
          newerId);
      jdbcTemplate.update(
          "delete from batch.scheduled_occurrence_coverage where tenant_id = ? and workflow_definition_id = ?",
          tenantId,
          newerId);
      jdbcTemplate.update(
          "delete from batch.workflow_definition where tenant_id = ? and id = ?",
          tenantId,
          newerId);
    }
  }

  private String occurrenceStatus(Instant scheduledFireTime) {
    return jdbcTemplate.queryForObject(
        "select occurrence_status from batch.scheduled_occurrence where tenant_id = ? "
            + "and workflow_definition_id = ? and scheduled_fire_time = ?",
        String.class,
        tenantId,
        workflowDefinitionId,
        java.sql.Timestamp.from(scheduledFireTime));
  }

  private int recoveryTriggerCount(Instant scheduledFireTime) throws Exception {
    return jdbcTemplate.queryForObject(
        """
        select count(*)::int from quartz.QRTZ_TRIGGERS
         where SCHED_NAME = ? and TRIGGER_GROUP = 'batch-trigger-recovery'
           and TRIGGER_NAME = ?
        """,
        Integer.class,
        scheduler.getSchedulerName(),
        "misfire-recovery-" + workflowDefinitionId + "-" + scheduledFireTime.toEpochMilli());
  }

  private SchedulerFactoryBean newSchedulerFactory() throws Exception {
    SchedulerFactoryBean factory = new SchedulerFactoryBean();
    factory.setSchedulerName(scheduler.getSchedulerName());
    factory.setDataSource(dataSource);
    factory.setAutoStartup(false);
    factory.setWaitForJobsToCompleteOnShutdown(true);
    factory.setQuartzProperties(restartQuartzProperties());
    factory.setJobFactory(springBeanJobFactory());
    return factory;
  }

  private Properties restartQuartzProperties() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("org.quartz.scheduler.instanceName", scheduler.getSchedulerName());
    properties.setProperty("org.quartz.scheduler.instanceId", "restart-" + UUID.randomUUID());
    properties.setProperty("org.quartz.jobStore.isClustered", "true");
    properties.setProperty("org.quartz.jobStore.tablePrefix", "quartz.QRTZ_");
    properties.setProperty(
        "org.quartz.jobStore.driverDelegateClass",
        "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
    properties.setProperty("org.quartz.threadPool.threadCount", "1");
    return properties;
  }

  private JobFactory springBeanJobFactory() {
    return new SpringBeanJobFactory() {
      @Override
      protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
        return beanFactory.createBean(bundle.getJobDetail().getJobClass());
      }
    };
  }

  private JobDataMap workflowJobData() {
    JobDataMap data = new JobDataMap();
    data.put(QuartzLaunchJob.TENANT_ID, tenantId);
    data.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, workflowDefinitionId);
    data.put(QuartzLaunchJob.WORKFLOW_CODE, workflowCode);
    data.put(QuartzLaunchJob.WORKFLOW_VERSION, 1);
    data.put(QuartzLaunchJob.JOB_DEFINITION_VERSION, 0);
    data.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
    data.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, "0 0 * * * ?");
    data.put(QuartzLaunchJob.TIMEZONE, "UTC");
    data.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
    data.put(QuartzLaunchJob.CALENDAR_CODE, "misfire_cal");
    data.put(QuartzLaunchJob.CATCH_UP_POLICY, "AUTO");
    data.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 3);
    return data;
  }

  private JobDataMap misfireIdentityData() {
    JobDataMap data = new JobDataMap();
    data.put(QuartzLaunchJob.TENANT_ID, tenantId);
    data.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, workflowDefinitionId);
    return data;
  }
}
