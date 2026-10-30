package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.infrastructure.QuartzLaunchJob;
import io.github.pinpols.batch.trigger.infrastructure.QuartzMisfireRecoveryListener;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.infrastructure.TriggerSchedulerFacade;
import io.github.pinpols.batch.trigger.infrastructure.scheduler.TriggerReconciler;
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import io.github.pinpols.batch.trigger.service.TriggerScheduledOccurrencePlanner;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobListener;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.TriggerListener;
import org.quartz.impl.matchers.EverythingMatcher;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.AopTestUtils;

/** 暂停会保留进行中的工作并记录其结果，但不得推进任何边。 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.security.bypass-mode=true",
      "spring.quartz.job-store-type=jdbc",
      "spring.quartz.jdbc.initialize-schema=always",
      "spring.quartz.properties.org.quartz.jobStore.misfireThreshold=60000",
      // 恢复进度器会在 PAUSED → RUNNING 后重新处理此持久化的 SUCCESS/FAILED 节点。
      // 节点 JobInstance 只拥有自己的分区。兄弟/后代 Workflow 节点会让
      "batch.trigger.reconcile-interval-millis=600000"
    })
@DisplayName("Quartz JDBC misfire 恢复:真实过期 Workflow Cron trigger 最终写入 CATCH_UP request 与 outbox")
class QuartzMisfireRecoveryIntegrationTest extends AbstractIntegrationTest {

  private Scheduler scheduler;

  private final JdbcTemplate jdbcTemplate;

  private final ScheduledOccurrenceRecorder occurrenceRecorder;

  private final QuartzMisfireRecoveryListener misfireRecoveryListener;

  private final TriggerReconciler triggerReconciler;

  private final TaskScheduler taskScheduler;

  private final DataSource dataSource;

  private final AutowireCapableBeanFactory beanFactory;

  @Autowired
  QuartzMisfireRecoveryIntegrationTest(
      Scheduler scheduler,
      JdbcTemplate jdbcTemplate,
      ScheduledOccurrenceRecorder occurrenceRecorder,
      QuartzMisfireRecoveryListener misfireRecoveryListener,
      TriggerReconciler triggerReconciler,
      TaskScheduler taskScheduler,
      DataSource dataSource,
      AutowireCapableBeanFactory beanFactory) {
    this.scheduler = scheduler;
    this.jdbcTemplate = jdbcTemplate;
    this.occurrenceRecorder = occurrenceRecorder;
    this.misfireRecoveryListener = misfireRecoveryListener;
    this.triggerReconciler = triggerReconciler;
    this.taskScheduler = taskScheduler;
    this.dataSource = dataSource;
    this.beanFactory = beanFactory;
  }

  private String tenantId;
  private String workflowCode;
  private Long workflowDefinitionId;
  private JobKey jobKey;
  private SchedulerFactoryBean replacementSchedulerFactory;
  private QuartzMisfireRecoveryListener replacementMisfireRecoveryListener;

  @BeforeEach
  void seedWorkflow() throws Exception {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertBusinessCalendarSql = """
          insert into batch.business_calendar (
              tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
              catch_up_policy, catch_up_max_days, cutoff_time, late_arrival_tolerance_min,
              sla_offset_min, enabled
          ) values (?, 'misfire_cal', 'Misfire calendar', 'UTC', 'SKIP', 'AUTO', 3,
                    ?, 30, 120, true)
          """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
              schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled, calendar_code
          ) values (?, ?, ?, 'DAG', 1, true, 'CRON', '0 0 * * * ?', 'UTC', 'SCHEDULED', true,
                    'misfire_cal') returning id
          """;

    if (scheduler.isShutdown()) {
      restartSchedulerForMisfire();
    }
    tenantId = "quartz-misfire-" + UUID.randomUUID();
    workflowCode = "WF_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, LocalTime.MIDNIGHT);
    workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, workflowCode);
    jobKey = JobKey.jobKey(tenantId + ":WF:" + workflowCode, TriggerSchedulerFacade.JOB_GROUP);
  }

  @AfterEach
  void cleanUp() throws Exception {
    final String deleteScheduledOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id = ?
    """;
    final String deleteScheduledOccurrenceCoverageSql = """
        delete from batch.scheduled_occurrence_coverage where tenant_id = ?
    """;
    final String deleteTriggerMisfirePendingSql = """
        delete from batch.trigger_misfire_pending where tenant_id = ?
    """;
    final String deleteTriggerOutboxEventSql = """
        delete from batch.trigger_outbox_event where tenant_id = ?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ?
    """;
    final String deleteCalendarHolidaySql = """
        delete from batch.calendar_holiday where tenant_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and id = ?
    """;
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id = ?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = ?
    """;
    final String deleteTenantSql = """
        delete from batch.tenant where tenant_id = ?
    """;

    boolean scheduledJobCanBeRemoved =
        jobKey != null && !scheduler.isShutdown() && scheduler.checkExists(jobKey);
    if (scheduledJobCanBeRemoved) {
      scheduler.deleteJob(jobKey);
    }
    if (tenantId == null) {
      return;
    }
    if (!scheduler.isShutdown()) {
      scheduler.deleteJob(JobKey.jobKey(
          tenantId + ":RECOVERY:" + workflowDefinitionId, TriggerSchedulerFacade.JOB_GROUP));
    }
    jdbcTemplate.update(deleteScheduledOccurrenceSql, tenantId);
    jdbcTemplate.update(deleteScheduledOccurrenceCoverageSql, tenantId);
    jdbcTemplate.update(deleteTriggerMisfirePendingSql, tenantId);
    jdbcTemplate.update(deleteTriggerOutboxEventSql, tenantId);
    jdbcTemplate.update(deleteTriggerRequestSql, tenantId);
    jdbcTemplate.update(deleteCalendarHolidaySql, tenantId);
    jdbcTemplate.update(deleteWorkflowDefinitionSql, tenantId, workflowDefinitionId);
    jdbcTemplate.update(deleteBatchDayCatchupControlSql, tenantId);
    jdbcTemplate.update(deleteBusinessCalendarSql, tenantId);
    jdbcTemplate.update(deleteTenantSql, tenantId);
    if (replacementSchedulerFactory != null) {
      replacementSchedulerFactory.destroy();
      replacementSchedulerFactory = null;
    }
  }

  @Test
  @DisplayName("恢复 JDBC 调度器后真实 misfire callback 保留原 fire time 并只产生一个补跑请求")
  void realQuartzMisfire_recoversWorkflowOccurrenceIntoCatchUpRequest() throws Exception {
    final String insertScheduledOccurrenceSql = """
          insert into batch.scheduled_occurrence (
              tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
              scheduled_fire_time, biz_date, occurrence_status
          ) values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
          """;
    final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
      and trigger_type = 'CATCH_UP'\
      """;
    final String selectCountSql2 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = ?
  """;
    final String selectBizDateSql = """
      select biz_date from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
      and trigger_type = 'CATCH_UP'\
      """;
    final String selectCountSql3 = """
      select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
      and scheduled_fire_time = ? and occurrence_status = 'REQUESTED'\
      """;

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

    restartSchedulerForMisfire();
    if (scheduler.checkExists(jobKey)) {
      scheduler.deleteJob(jobKey);
    }
    assertThat(scheduler.getListenerManager().getTriggerListeners())
        .extracting(TriggerListener::getName)
        .contains("quartzMisfireRecoveryListener");
    // WorkflowRun 保持活动，但不能让此已完成的 JobInstance 继续占用准入名额。
    // 硬超时路径已将所有活动子任务和分区置为终态后，重新协调 Workflow 节点。
    Date expectedScheduledFireTime =
        trigger.getFireTimeAfter(Date.from(triggerStartTime.toInstant().minusMillis(1)));
    assertThat(expectedScheduledFireTime)
        .as("the JDBC JobStore must persist an overdue cron fire before scheduler resume")
        .isBefore(Date.from(Instant.now().minusSeconds(60)));
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        expectedScheduledFireTime,
        expectedScheduledFireTime.toInstant().atZone(ZoneOffset.UTC).toLocalDate());
    scheduler.scheduleJob(job, trigger);
    scheduler.start();

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      Integer requestCount = jdbcTemplate.queryForObject(
          selectCountSql, Integer.class, tenantId, workflowDefinitionId);
      assertThat(requestCount).isEqualTo(1);
      Integer outboxCount = jdbcTemplate.queryForObject(selectCountSql2, Integer.class, tenantId);
      assertThat(outboxCount).isEqualTo(1);
      LocalDate requestBizDate = jdbcTemplate.queryForObject(
          selectBizDateSql, LocalDate.class, tenantId, workflowDefinitionId);
      assertThat(requestBizDate)
          .isEqualTo(
              expectedScheduledFireTime.toInstant().atZone(ZoneOffset.UTC).toLocalDate());
    });

    Integer occurrenceCount = jdbcTemplate.queryForObject(
        selectCountSql3, Integer.class, tenantId, workflowDefinitionId, expectedScheduledFireTime);
    assertThat(occurrenceCount).isEqualTo(1);
  }

  @Test
  @DisplayName("Quartz 跨过多个已规划 fire 后对账不能只恢复首个而遗留其余 PLANNED")
  void realQuartzMisfire_shouldAccountForEveryPersistedMissedFire() throws Exception {
    final String insertScheduledOccurrenceSql = """
            insert into batch.scheduled_occurrence (
                tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
                scheduled_fire_time, biz_date, occurrence_status
            ) values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
            """;
    final String selectCountSql = """
              select count(*) from batch.scheduled_occurrence
              where tenant_id = ? and workflow_definition_id = ?
                and occurrence_status = 'PLANNED' and scheduled_fire_time < ?
              """;
    final String selectCountSql2 = """
      select count(*) from batch.trigger_request where tenant_id = ?
  """;
    final String selectCountSql3 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = ?
  """;

    Instant now = Instant.now();
    Date triggerStart = Date.from(now.minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.HOURS));
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(workflowJobData())
        .storeDurably()
        .build();
    CronTrigger trigger = TriggerBuilder.newTrigger()
        .withIdentity(TriggerKey.triggerKey(jobKey.getName(), jobKey.getGroup()))
        .forJob(job)
        .usingJobData(misfireIdentityData())
        .startAt(triggerStart)
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?")
            .inTimeZone(TimeZone.getTimeZone("UTC"))
            .withMisfireHandlingInstructionDoNothing())
        .build();
    restartSchedulerForMisfire();
    List<Instant> missedFires = new ArrayList<>();
    Date fire = trigger.getFireTimeAfter(new Date(triggerStart.getTime() - 1));
    while (fire.toInstant().isBefore(now.minusSeconds(60))) {
      Instant scheduledFire = fire.toInstant();
      jdbcTemplate.update(
          insertScheduledOccurrenceSql,
          tenantId,
          workflowDefinitionId,
          workflowCode,
          fire,
          scheduledFire.atZone(ZoneOffset.UTC).toLocalDate());
      missedFires.add(scheduledFire);
      fire = trigger.getFireTimeAfter(fire);
    }
    assertThat(missedFires).hasSizeGreaterThanOrEqualTo(3);
    scheduler.scheduleJob(job, trigger);
    scheduler.start();
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> assertThat(occurrenceStatus(missedFires.getFirst())).isEqualTo("REQUESTED"));
    assertThat(scheduler.getTrigger(trigger.getKey()).getNextFireTime()).isAfter(Date.from(now));

    // 不同于 Worker REPORT，此处不得再次改写
    // JobInstance；只复用标准 DAG 失败传播和 Workflow 收尾逻辑。
    var definitionLoader = beanFactory.getBean(TriggerDefinitionLoader.class);
    var scopedDefinitions = mock(TriggerDefinitionLoader.class);
    when(scopedDefinitions.loadAll())
        .thenReturn(List.of(definitionLoader.loadByWorkflowCode(tenantId, workflowCode)));
    when(scopedDefinitions.loadPendingMisfireDefinitions()).thenReturn(List.of());
    var registration = mock(TriggerRegistrationService.class);
    TriggerReconciler reconciler = new TriggerReconciler(
        scopedDefinitions,
        registration,
        scheduler,
        beanFactory.getBean(TriggerGracefulShutdown.class),
        beanFactory.getBean(TriggerScheduledOccurrencePlanner.class),
        replacementMisfireRecoveryListener);
    reconciler.reconcile();
    verify(registration, never()).registerRecoveryJob(any());

    Integer selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(now.minusSeconds(60)));
    assertThat(selectedScheduledOccurrenceCount)
        .as(
            "Every persisted fire already skipped by Quartz must enter recovery or a policy terminal")
        .isZero();
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      for (Instant scheduledFire : missedFires) {
        assertThat(occurrenceStatus(scheduledFire)).isEqualTo("REQUESTED");
      }
      Integer selectedTriggerRequestCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, tenantId);
      assertThat(selectedTriggerRequestCount).isEqualTo(missedFires.size());
      Integer selectedTriggerOutboxEventCount =
          jdbcTemplate.queryForObject(selectCountSql3, Integer.class, tenantId);
      assertThat(selectedTriggerOutboxEventCount).isEqualTo(missedFires.size());
    });
  }

  @Test
  @DisplayName("Quartz 恢复 DST misfire 保留原 fire instant 并按 cutoff 与节假日滚动冻结业务日")
  void quartzRecoveryForDstMisfire_persistsHolidayRolledBusinessDate() throws Exception {
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set timezone = 'America/New_York', cutoff_time = '02:30:00',\s\
        holiday_roll_rule = 'NEXT_WORKDAY' where tenant_id = ? and calendar_code = 'misfire_cal'\
        """;
    final String selectIdSql = """
        select id from batch.business_calendar where tenant_id = ? and calendar_code = ?
    """;
    final String insertCalendarHolidaySql = """
        insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, scope)\s\
        values (?, ?, ?, 'HOLIDAY', 'CALENDAR')\
        """;
    final String insertScheduledOccurrenceSql = """
          insert into batch.scheduled_occurrence (
              tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
              scheduled_fire_time, biz_date, occurrence_status
          ) values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
          """;
    final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\
      """;
    final String selectBizDateSql = """
      select biz_date from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\
      """;
    final String selectScheduledFireTimeSql = """
      select scheduled_fire_time from batch.scheduled_occurrence where tenant_id = ?\s\
      and workflow_definition_id = ?\
      """;
    final String selectBizDateSql2 = """
      select biz_date from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
      and scheduled_fire_time = ?\
      """;

    Instant scheduledFireTime = Instant.parse("2026-03-08T07:00:00Z");
    LocalDate rawBizDate = LocalDate.of(2026, 3, 8);
    LocalDate rolledBizDate = LocalDate.of(2026, 3, 9);
    jdbcTemplate.update(updateBusinessCalendarSql, tenantId);
    Long calendarId = jdbcTemplate.queryForObject(selectIdSql, Long.class, tenantId, "misfire_cal");
    jdbcTemplate.update(insertCalendarHolidaySql, tenantId, calendarId, rawBizDate);
    assertThat(scheduledFireTime.atZone(ZoneId.of("America/New_York")).toLocalTime())
        .isEqualTo(LocalTime.of(3, 0));

    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(workflowJobData())
        .storeDurably()
        .build();
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        Timestamp.from(scheduledFireTime),
        rolledBizDate);

    restartSchedulerForMisfire();
    if (scheduler.checkExists(jobKey)) {
      scheduler.deleteJob(jobKey);
    }
    scheduler.addJob(job, true);
    assertThat(occurrenceRecorder.markMisfirePending(
            tenantId, workflowDefinitionId, scheduledFireTime))
        .isTrue();
    replacementMisfireRecoveryListener.restorePendingMisfires(
        tenantId, workflowDefinitionId, workflowCode);
    scheduler.start();

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      Integer requestCount = jdbcTemplate.queryForObject(
          selectCountSql, Integer.class, tenantId, workflowDefinitionId);
      assertThat(requestCount).isEqualTo(1);
      LocalDate selectedBizDate = jdbcTemplate.queryForObject(
          selectBizDateSql, LocalDate.class, tenantId, workflowDefinitionId);
      assertThat(selectedBizDate).isEqualTo(rolledBizDate);
    });
    assertThat(occurrenceStatus(scheduledFireTime)).isEqualTo("REQUESTED");
    Timestamp selectedScheduledFireTime = jdbcTemplate.queryForObject(
        selectScheduledFireTimeSql, Timestamp.class, tenantId, workflowDefinitionId);
    assertThat(selectedScheduledFireTime).isEqualTo(Timestamp.from(scheduledFireTime));
    LocalDate selectedBizDateRead2 = jdbcTemplate.queryForObject(
        selectBizDateSql2,
        LocalDate.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(scheduledFireTime));
    assertThat(selectedBizDateRead2).isEqualTo(rolledBizDate);
  }

  @Test
  @DisplayName("真实 Quartz misfire 与并发重复恢复报告同一 fire 时只接纳一条 request 和 Outbox")
  void realQuartzDuplicateMisfireCallbacksForSameFire_persistOneRequestAndOutbox()
      throws Exception {
    final String insertScheduledOccurrenceSql = """
    insert into batch.scheduled_occurrence (
    tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
    scheduled_fire_time, biz_date, occurrence_status
    ) values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
    """;
    final String selectCountSql = """
            select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
            and trigger_type = 'CATCH_UP'\
            """;
    final String selectCountSql2 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = ?
  """;
    final String selectCountSql3 = """
      select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
      and scheduled_fire_time = ? and occurrence_status = 'REQUESTED'\
      """;

    Date triggerStartTime = Date.from(Instant.now().minus(Duration.ofHours(3)));
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(workflowJobData())
        .storeDurably()
        .build();
    CronTrigger firstTrigger = duplicateMisfireTrigger("duplicate-a", jobKey, triggerStartTime);
    Date expectedScheduledFireTime =
        firstTrigger.getFireTimeAfter(Date.from(triggerStartTime.toInstant().minusMillis(1)));
    assertThat(expectedScheduledFireTime)
        .as("the JDBC JobStore must persist two overdue triggers for the same scheduled fire")
        .isBefore(Date.from(Instant.now().minusSeconds(60)));

    restartSchedulerForMisfire();
    if (scheduler.checkExists(jobKey)) {
      scheduler.deleteJob(jobKey);
    }
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        expectedScheduledFireTime,
        expectedScheduledFireTime.toInstant().atZone(ZoneOffset.UTC).toLocalDate());
    scheduler.scheduleJob(job, firstTrigger);
    AtomicInteger observedMisfireCallbacks = new AtomicInteger();
    TriggerListener callbackObserver = new TriggerListener() {
      @Override
      public String getName() {
        return "duplicate-fire-callback-observer";
      }

      @Override
      public void triggerFired(Trigger trigger, JobExecutionContext context) {}

      @Override
      public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
        return false;
      }

      @Override
      public void triggerMisfired(Trigger trigger) {
        observedMisfireCallbacks.incrementAndGet();
      }

      @Override
      public void triggerComplete(
          Trigger trigger,
          JobExecutionContext context,
          Trigger.CompletedExecutionInstruction triggerInstructionCode) {}
    };
    scheduler
        .getListenerManager()
        .addTriggerListener(callbackObserver, EverythingMatcher.allTriggers());
    scheduler.start();

    Trigger duplicateCallback = mock(Trigger.class);
    when(duplicateCallback.getKey()).thenReturn(firstTrigger.getKey());
    when(duplicateCallback.getJobKey()).thenReturn(jobKey);
    when(duplicateCallback.getJobDataMap()).thenReturn(misfireIdentityData());
    when(duplicateCallback.getNextFireTime()).thenReturn(expectedScheduledFireTime);
    ExecutorService duplicateRecoveryPool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch callbacksReady = new CountDownLatch(2);
      CountDownLatch callbacksStart = new CountDownLatch(1);
      Future<?> firstRecovery = duplicateRecoveryPool.submit(() -> {
        callbacksReady.countDown();
        callbacksStart.await(5, TimeUnit.SECONDS);
        replacementMisfireRecoveryListener.triggerMisfired(duplicateCallback);
        return null;
      });
      Future<?> secondRecovery = duplicateRecoveryPool.submit(() -> {
        callbacksReady.countDown();
        callbacksStart.await(5, TimeUnit.SECONDS);
        replacementMisfireRecoveryListener.triggerMisfired(duplicateCallback);
        return null;
      });
      assertThat(callbacksReady.await(5, TimeUnit.SECONDS)).isTrue();
      callbacksStart.countDown();
      firstRecovery.get(10, TimeUnit.SECONDS);
      secondRecovery.get(10, TimeUnit.SECONDS);
    } finally {
      duplicateRecoveryPool.shutdownNow();
    }

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      Integer requestCount = jdbcTemplate.queryForObject(
          selectCountSql, Integer.class, tenantId, workflowDefinitionId);
      assertThat(requestCount).isEqualTo(1);
      Integer outboxCount = jdbcTemplate.queryForObject(selectCountSql2, Integer.class, tenantId);
      assertThat(outboxCount).isEqualTo(1);
      Integer occurrenceCount = jdbcTemplate.queryForObject(
          selectCountSql3,
          Integer.class,
          tenantId,
          workflowDefinitionId,
          expectedScheduledFireTime);
      assertThat(occurrenceCount).isEqualTo(1);
    });
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(observedMisfireCallbacks.get())
            .as("the overdue Quartz trigger must deliver a real misfire callback")
            .isGreaterThanOrEqualTo(1));
  }

  @Test
  @DisplayName("真实 Quartz 恢复积压期间旧项受阻,多轮新义务持续推进且重复扫描不重复接纳")
  void realQuartzRecoveryBacklog_allowsNewArrivalAfterCapacityFrees() throws Exception {
    final String insertScheduledOccurrenceSql = """
            insert into batch.scheduled_occurrence (
                tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
                scheduled_fire_time, biz_date, occurrence_status
            ) values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
            """;
    final String selectCountSql = """
      select count(*) from quartz.QRTZ_TRIGGERS where SCHED_NAME = ?\s\
      and TRIGGER_GROUP = 'batch-trigger-recovery'\
      """;
    final String selectCountSql2 = """
      select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
      and occurrence_status = 'MISFIRE_PENDING'\
      """;
    final String insertScheduledOccurrenceSql2 = """
              insert into batch.scheduled_occurrence (
                  tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
                  scheduled_fire_time, biz_date, occurrence_status
              ) values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
              """;
    final String selectCountSql3 = """
      select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
      and occurrence_status = 'REQUESTED'\
      """;
    final String selectCountSql4 = """
      select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
      and trigger_type = 'CATCH_UP'\
      """;
    final String selectCountSql5 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = ?
  """;
    final String selectCountSql6 = """
      select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
      and occurrence_status = 'REQUESTED'\
      """;
    final String selectCountSql7 = """
            select count(*) from batch.scheduled_occurrence occurrence
            join batch.trigger_request request
              on request.tenant_id = occurrence.tenant_id
             and request.request_id = occurrence.request_id
             and request.workflow_definition_id = occurrence.workflow_definition_id
             and request.biz_date = occurrence.biz_date
             and request.trigger_type = 'CATCH_UP'
            join batch.trigger_outbox_event outbox
              on outbox.tenant_id = request.tenant_id and outbox.request_id = request.request_id
            where occurrence.tenant_id = ? and occurrence.workflow_definition_id = ?
              and occurrence.occurrence_status = 'REQUESTED'
            """;

    int backlogSize = 100;
    int arrivalWaves = 3;
    int arrivalsPerWave = 10;
    restartSchedulerForMisfire(8);
    Instant firstFireTime =
        Instant.now().minus(Duration.ofHours(48)).truncatedTo(ChronoUnit.SECONDS);
    LocalDate bizDate = firstFireTime.atZone(ZoneOffset.UTC).toLocalDate();
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(workflowJobData())
        .storeDurably()
        .build();
    scheduler.standby();
    if (scheduler.checkExists(jobKey)) {
      scheduler.deleteJob(jobKey);
    }
    scheduler.addJob(job, true);
    for (int index = 0; index < backlogSize; index++) {
      Instant fireTime = firstFireTime.plusSeconds(index);
      jdbcTemplate.update(
          insertScheduledOccurrenceSql,
          tenantId,
          workflowDefinitionId,
          workflowCode,
          Timestamp.from(fireTime),
          bizDate);
      assertThat(occurrenceRecorder.markMisfirePending(tenantId, workflowDefinitionId, fireTime))
          .isTrue();
    }
    currentMisfireRecoveryListener()
        .restorePendingMisfires(tenantId, workflowDefinitionId, workflowCode);
    Integer selectedQrtzTriggersCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, scheduler.getSchedulerName());
    assertThat(selectedQrtzTriggersCount).isEqualTo(backlogSize);

    CountDownLatch firstExecutionBlocked = new CountDownLatch(1);
    CountDownLatch releaseBlockedExecution = new CountDownLatch(1);
    Set<Long> executedNewArrivals = ConcurrentHashMap.newKeySet();
    AtomicBoolean blockedFirstExecution = new AtomicBoolean();
    AtomicLong blockedFireTime = new AtomicLong();
    Instant firstNewArrival = firstFireTime.plusSeconds(backlogSize);
    JobListener executionGate = new JobListener() {
      @Override
      public String getName() {
        return "r05-misfire-backlog-execution-gate";
      }

      @Override
      public void jobToBeExecuted(JobExecutionContext context) {
        if (!"batch-trigger-recovery".equals(context.getTrigger().getKey().getGroup())) {
          return;
        }
        long originalFireTime = context
            .getTrigger()
            .getJobDataMap()
            .getLongValue(QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME);
        if (originalFireTime >= firstNewArrival.toEpochMilli()) {
          executedNewArrivals.add(originalFireTime);
        }
        if (blockedFirstExecution.compareAndSet(false, true)) {
          blockedFireTime.set(originalFireTime);
          firstExecutionBlocked.countDown();
          try {
            if (!releaseBlockedExecution.await(60, TimeUnit.SECONDS)) {
              throw new IllegalStateException("timed out waiting to release gated recovery job");
            }
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while gating recovery job", exception);
          }
        }
      }

      @Override
      public void jobExecutionVetoed(JobExecutionContext context) {
        // 空操作
      }

      @Override
      public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        // no-op
      }
    };
    scheduler.getListenerManager().addJobListener(executionGate);
    scheduler.start();
    try {
      assertThat(firstExecutionBlocked.await(10, TimeUnit.SECONDS)).isTrue();
      await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
        Integer selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
            selectCountSql2, Integer.class, tenantId, workflowDefinitionId);
        assertThat(selectedScheduledOccurrenceCount).isEqualTo(1);
      });

      for (int wave = 0; wave < arrivalWaves; wave++) {
        for (int index = 0; index < arrivalsPerWave; index++) {
          Instant arrival = firstNewArrival.plusSeconds((long) wave * arrivalsPerWave + index);
          jdbcTemplate.update(
              insertScheduledOccurrenceSql2,
              tenantId,
              workflowDefinitionId,
              workflowCode,
              Timestamp.from(arrival),
              bizDate);
          assertThat(occurrenceRecorder.markMisfirePending(tenantId, workflowDefinitionId, arrival))
              .isTrue();
        }
        currentMisfireRecoveryListener()
            .restorePendingMisfires(tenantId, workflowDefinitionId, workflowCode);
        currentMisfireRecoveryListener()
            .restorePendingMisfires(tenantId, workflowDefinitionId, workflowCode);
        int expectedNewArrivals = (wave + 1) * arrivalsPerWave;
        int expectedRequests = backlogSize - 1 + expectedNewArrivals;
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
          Integer selectedScheduledOccurrenceCountRead2 = jdbcTemplate.queryForObject(
              selectCountSql3, Integer.class, tenantId, workflowDefinitionId);
          assertThat(selectedScheduledOccurrenceCountRead2).isEqualTo(expectedRequests);
          assertThat(executedNewArrivals).hasSize(expectedNewArrivals);
        });
        assertThat(occurrenceStatus(Instant.ofEpochMilli(blockedFireTime.get())))
            .as("the blocked old execution must remain pending while new waves make progress")
            .isEqualTo("MISFIRE_PENDING");
        Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
            selectCountSql4, Integer.class, tenantId, workflowDefinitionId);
        assertThat(selectedTriggerRequestCount).isEqualTo(expectedRequests);
        Integer selectedTriggerOutboxEventCount =
            jdbcTemplate.queryForObject(selectCountSql5, Integer.class, tenantId);
        assertThat(selectedTriggerOutboxEventCount).isEqualTo(expectedRequests);
      }
    } finally {
      releaseBlockedExecution.countDown();
      scheduler.getListenerManager().removeJobListener(executionGate.getName());
      if (scheduler.isInStandbyMode()) {
        scheduler.start();
      }
    }

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      Integer selectedScheduledOccurrenceCountRead3 = jdbcTemplate.queryForObject(
          selectCountSql6, Integer.class, tenantId, workflowDefinitionId);
      assertThat(selectedScheduledOccurrenceCountRead3)
          .isEqualTo(backlogSize + arrivalWaves * arrivalsPerWave);
    });
    currentMisfireRecoveryListener()
        .restorePendingMisfires(tenantId, workflowDefinitionId, workflowCode);
    Integer selectedScheduledOccurrenceCountRead4 =
        jdbcTemplate.queryForObject(selectCountSql7, Integer.class, tenantId, workflowDefinitionId);
    assertThat(selectedScheduledOccurrenceCountRead4)
        .isEqualTo(backlogSize + arrivalWaves * arrivalsPerWave);
    assertThat(occurrenceRecorder.pendingMisfires(tenantId, workflowDefinitionId, 100))
        .isEmpty();
  }

  @Test
  @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
  @DisplayName("Quartz Scheduler 重启后从 MISFIRE_PENDING 台账重建 one-shot 并接纳一个补跑")
  void restartedQuartzScheduler_restoresPendingRecoveryFromDatabase() throws Exception {
    final String insertScheduledOccurrenceSql = """
          insert into batch.scheduled_occurrence (
              tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
              scheduled_fire_time, biz_date, occurrence_status
          ) values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
          """;
    final String selectOccurrenceStatusSql = """
      select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
      and workflow_definition_id = ? and scheduled_fire_time = ?\
      """;
    final String selectCountSql = """
            select count(*)::int
              from quartz.QRTZ_TRIGGERS
             where SCHED_NAME = ? and TRIGGER_GROUP = 'batch-trigger-recovery'
               and TRIGGER_NAME = ?
            """;
    final String selectCountSql2 = """
      select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
      and trigger_type = 'CATCH_UP'\
      """;
    final String selectCountSql3 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = ?
  """;
    final String selectCountSql4 = """
      select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
      and scheduled_fire_time = ? and occurrence_status = 'REQUESTED'\
      """;

    Instant originalFireTime =
        Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MINUTES);
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(workflowJobData())
        .storeDurably()
        .build();
    scheduler.addJob(job, true);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        Timestamp.from(originalFireTime),
        originalFireTime.atZone(ZoneOffset.UTC).toLocalDate());

    // JobInstance 超时并结束其子项后，推进所属 Workflow DAG。
    // WorkflowRun 从 PAUSED 恢复后，以幂等方式重新执行 DAG 推进。
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
        () -> scheduler, lostHandoffScheduler, occurrenceRecorder, new TriggerRuntimeProperties());
    interruptedListener.triggerMisfired(missedTrigger);
    String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(originalFireTime));
    assertThat(selectedOccurrenceStatus).isEqualTo("MISFIRE_PENDING");

    // 同一正式/试运行模式下，可供跨日依赖使用的最新版本。
    // 仅查找同一正式/试运行执行模式中的指定版本。
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
          () -> restartedScheduler,
          taskScheduler,
          occurrenceRecorder,
          new TriggerRuntimeProperties());
      QuartzMisfireRecoveryListener competingRecovery = new QuartzMisfireRecoveryListener(
          () -> competingScheduler,
          taskScheduler,
          occurrenceRecorder,
          new TriggerRuntimeProperties());
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
          selectCountSql,
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
            selectCountSql2, Integer.class, tenantId, workflowDefinitionId);
        assertThat(requestCount).isEqualTo(1);
        Integer outboxCount = jdbcTemplate.queryForObject(selectCountSql3, Integer.class, tenantId);
        assertThat(outboxCount)
            .as("competing Quartz recovery nodes must publish only one launch outbox event")
            .isEqualTo(1);
        Integer occurrenceCount = jdbcTemplate.queryForObject(
            selectCountSql4,
            Integer.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(originalFireTime));
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
    final String insertScheduledOccurrenceSql = """
    insert into batch.scheduled_occurrence (
    tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
    scheduled_fire_time, biz_date, occurrence_status
    ) values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
    """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set schedule_enabled = false, enabled = ? where tenant_id = ?\s\
            and id = ?\
            """;
    final String updateTenantSql = """
            update batch.tenant set status = 'SUSPENDED' where tenant_id = ?
        """;
    final String selectCountSql = """
            select count(*) from batch.trigger_request where tenant_id = ?
        """;
    final String updateTenantSql2 = """
      update batch.tenant set status = 'ACTIVE' where tenant_id = ?
  """;
    final String selectCountSql2 = """
      select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
      and trigger_type = 'CATCH_UP'\
      """;
    final String selectScheduleEnabledSql = """
            select schedule_enabled from batch.workflow_definition where tenant_id = ? and id = ?
        """;
    final String selectEnabledSql = """
            select enabled from batch.workflow_definition where tenant_id=? and id=?
        """;
    final String selectCountSql3 = """
      select count(*) from batch.trigger_request where tenant_id = ? and trigger_type = 'CATCH_UP'
  """;

    Instant originalFireTime =
        Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MINUTES);
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(workflowJobData())
        .storeDurably()
        .build();
    scheduler.addJob(job, true);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        Timestamp.from(originalFireTime),
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
    new QuartzMisfireRecoveryListener(
            () -> scheduler,
            lostHandoffScheduler,
            occurrenceRecorder,
            new TriggerRuntimeProperties())
        .triggerMisfired(missedTrigger);

    jdbcTemplate.update(
        updateWorkflowDefinitionSql, !disableWorkflow, tenantId, workflowDefinitionId);
    jdbcTemplate.update(updateTenantSql, tenantId);
    // 所有前驱都已终态但汇合规则仍不满足时，此节点不能触发。
    AopTestUtils.<TriggerReconciler>getTargetObject(triggerReconciler).reconcile();
    assertThat(scheduler.checkExists(jobKey)).isFalse();
    assertThat(occurrenceStatus(originalFireTime)).isEqualTo("MISFIRE_PENDING");
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId);
    assertThat(selectedTriggerRequestCount).isZero();

    jdbcTemplate.update(updateTenantSql2, tenantId);
    AopTestUtils.<TriggerReconciler>getTargetObject(triggerReconciler).reconcile();

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      Integer selectedTriggerRequestCountRead2 = jdbcTemplate.queryForObject(
          selectCountSql2, Integer.class, tenantId, workflowDefinitionId);
      assertThat(selectedTriggerRequestCountRead2).isEqualTo(1);
    });
    assertThat(occurrenceStatus(originalFireTime)).isEqualTo("REQUESTED");
    Boolean selectedScheduleEnabled = jdbcTemplate.queryForObject(
        selectScheduleEnabledSql, Boolean.class, tenantId, workflowDefinitionId);
    assertThat(selectedScheduleEnabled).isFalse();
    Boolean selectedEnabled = jdbcTemplate.queryForObject(
        selectEnabledSql, Boolean.class, tenantId, workflowDefinitionId);
    assertThat(selectedEnabled).isEqualTo(!disableWorkflow);
    assertThat(scheduler.getTrigger(TriggerKey.triggerKey(jobKey.getName(), jobKey.getGroup())))
        .isNull();
    AopTestUtils.<TriggerReconciler>getTargetObject(triggerReconciler).reconcile();
    Integer selectedTriggerRequestCountRead3 =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class, tenantId);
    assertThat(selectedTriggerRequestCountRead3).isEqualTo(1);
  }

  @Test
  @DisplayName("旧定义的待恢复 fire 使用独立恢复目标,不借用同编码新定义的主调度参数")
  void olderPendingMisfire_shouldKeepItsDefinitionWhenNewerPrimaryIsRegistered() throws Exception {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set schedule_enabled = false where id = ?
    """;
    final String insertScheduledOccurrenceSql = """
          insert into batch.scheduled_occurrence (
              tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
              scheduled_fire_time, biz_date, occurrence_status)
          values (?, ?, ?, 1, 'misfire_cal', ?, ?, 'PLANNED')
          """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
              schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled, calendar_code)
          values (?, ?, 'Newer primary', 'DAG', 2, true, 'CRON',
              '0 0 0 1 1 ? 2099', 'UTC', 'SCHEDULED', true, 'misfire_cal') returning id
          """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?
    """;
    final String deleteScheduledOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?
    """;
    final String deleteScheduledOccurrenceCoverageSql = """
        delete from batch.scheduled_occurrence_coverage where tenant_id = ? and workflow_definition_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and id = ?
    """;

    Instant fire = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MINUTES);
    jdbcTemplate.update(updateWorkflowDefinitionSql, workflowDefinitionId);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        Timestamp.from(fire),
        fire.atZone(ZoneOffset.UTC).toLocalDate());
    assertThat(occurrenceRecorder.markMisfirePending(tenantId, workflowDefinitionId, fire))
        .isTrue();
    Long newerId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode);
    try {
      if (!scheduler.isStarted()) {
        scheduler.start();
      }
      reconcilerForCurrentScheduler().reconcile();
      await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
        Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
            selectCountSql, Integer.class, tenantId, workflowDefinitionId);
        assertThat(selectedTriggerRequestCount).isEqualTo(1);
        assertThat(occurrenceStatus(fire)).isEqualTo("REQUESTED");
      });
      Integer selectedTriggerRequestCountRead2 =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, newerId);
      assertThat(selectedTriggerRequestCountRead2).isZero();
      assertThat(scheduler
              .getJobDetail(jobKey)
              .getJobDataMap()
              .getLong(QuartzLaunchJob.WORKFLOW_DEFINITION_ID))
          .isEqualTo(newerId);
    } finally {
      jdbcTemplate.update(deleteScheduledOccurrenceSql, tenantId, newerId);
      jdbcTemplate.update(deleteScheduledOccurrenceCoverageSql, tenantId, newerId);
      jdbcTemplate.update(deleteWorkflowDefinitionSql, tenantId, newerId);
    }
  }

  private String occurrenceStatus(Instant scheduledFireTime) {
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;

    return jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(scheduledFireTime));
  }

  private int recoveryTriggerCount(Instant scheduledFireTime) throws Exception {
    final String selectCountSql = """
          select count(*)::int from quartz.QRTZ_TRIGGERS
           where SCHED_NAME = ? and TRIGGER_GROUP = 'batch-trigger-recovery'
             and TRIGGER_NAME = ?
          """;

    return jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        scheduler.getSchedulerName(),
        "misfire-recovery-" + workflowDefinitionId + "-" + scheduledFireTime.toEpochMilli());
  }

  private SchedulerFactoryBean newSchedulerFactory() throws Exception {
    return newSchedulerFactory(1);
  }

  private SchedulerFactoryBean newSchedulerFactory(int threadCount) throws Exception {
    SchedulerFactoryBean factory = new SchedulerFactoryBean();
    factory.setSchedulerName(scheduler.getSchedulerName());
    factory.setDataSource(dataSource);
    factory.setAutoStartup(false);
    factory.setWaitForJobsToCompleteOnShutdown(true);
    Properties properties = restartQuartzProperties();
    properties.setProperty("org.quartz.threadPool.threadCount", Integer.toString(threadCount));
    factory.setQuartzProperties(properties);
    factory.setJobFactory(springBeanJobFactory());
    return factory;
  }

  private void restartSchedulerForMisfire() throws Exception {
    restartSchedulerForMisfire(1);
  }

  private void restartSchedulerForMisfire(int threadCount) throws Exception {
    if (!scheduler.isShutdown()) {
      scheduler.shutdown(true);
    }
    replacementSchedulerFactory = newSchedulerFactory(threadCount);
    replacementSchedulerFactory.afterPropertiesSet();
    scheduler = replacementSchedulerFactory.getScheduler();
    replacementMisfireRecoveryListener = new QuartzMisfireRecoveryListener(
        () -> scheduler, taskScheduler, occurrenceRecorder, new TriggerRuntimeProperties());
    scheduler
        .getListenerManager()
        .addTriggerListener(replacementMisfireRecoveryListener, EverythingMatcher.allTriggers());
  }

  private QuartzMisfireRecoveryListener currentMisfireRecoveryListener() {
    return replacementMisfireRecoveryListener == null
        ? misfireRecoveryListener
        : replacementMisfireRecoveryListener;
  }

  private TriggerReconciler reconcilerForCurrentScheduler() {
    var definitionLoader = beanFactory.getBean(TriggerDefinitionLoader.class);
    return new TriggerReconciler(
        definitionLoader,
        new TriggerSchedulerFacade(definitionLoader, scheduler),
        scheduler,
        beanFactory.getBean(TriggerGracefulShutdown.class),
        beanFactory.getBean(TriggerScheduledOccurrencePlanner.class),
        currentMisfireRecoveryListener());
  }

  private CronTrigger duplicateMisfireTrigger(String suffix, JobKey targetJobKey, Date startTime) {
    return TriggerBuilder.newTrigger()
        .withIdentity("duplicate-misfire-" + suffix, TriggerSchedulerFacade.JOB_GROUP)
        .forJob(targetJobKey)
        .usingJobData(misfireIdentityData())
        .startAt(startTime)
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?")
            .inTimeZone(TimeZone.getTimeZone("UTC"))
            .withMisfireHandlingInstructionDoNothing())
        .build();
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
