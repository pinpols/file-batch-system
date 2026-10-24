package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.infrastructure.QuartzLaunchJob;
import io.github.pinpols.batch.trigger.infrastructure.QuartzMisfireRecoveryListener;
import io.github.pinpols.batch.trigger.infrastructure.TriggerSchedulerFacade;
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import io.github.pinpols.batch.trigger.service.TriggerScheduledOccurrencePlanner;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.TaskScheduler;

/** 根据围栏后的快照检查所有者和调用标识，不使用加锁前的读取结果。 */
@DisplayName("Trigger 完整应用上下文重启:从 PostgreSQL 恢复持久 misfire")
class QuartzMisfireApplicationRestartIntegrationTest extends AbstractIntegrationTest {

  private static final String SELECT_SCHEDULED_OCCURRENCE_COUNT_BY_TENANT_SQL = """
      select count(*) from batch.scheduled_occurrence where tenant_id = ?
      """;
  private static final String SELECT_SCHEDULED_OCCURRENCE_COVERAGE_COUNT_BY_TENANT_SQL = """
      select count(*) from batch.scheduled_occurrence_coverage where tenant_id = ?
      """;

  private String isolatedDatabaseName;
  private String isolatedJdbcUrl;
  private String orchestratorBaseUrlOverride;

  @BeforeEach
  void isolatePlatformDatabaseFromCachedSchedulerContexts() throws Exception {
    isolatedDatabaseName = "quartz_restart_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(
            platformJdbcUrl(), platformJdbcUsername(), platformJdbcPassword());
        var statement = connection.createStatement()) {
          final String createDatabaseSql = """
              CREATE DATABASE \"%s\"
          """.formatted(isolatedDatabaseName);
      statement.execute(createDatabaseSql);
    }
    String sharedUrl = platformJdbcUrl();
    int databaseStart = sharedUrl.indexOf('/', "jdbc:postgresql://".length());
    int queryStart = sharedUrl.indexOf('?', databaseStart);
    isolatedJdbcUrl = sharedUrl.substring(0, databaseStart + 1)
        + isolatedDatabaseName
        + (queryStart < 0 ? "" : sharedUrl.substring(queryStart));
    try (var connection = DriverManager.getConnection(
        isolatedJdbcUrl, platformJdbcUsername(), platformJdbcPassword())) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/platform-init.sql"));
    }
  }

  @AfterEach
  void dropIsolatedPlatformDatabase() throws Exception {
    if (EmptyChecks.isBlank(isolatedDatabaseName)) {
      return;
    }
    try (var connection = DriverManager.getConnection(
            platformJdbcUrl(), platformJdbcUsername(), platformJdbcPassword());
        var statement = connection.createStatement()) {
          final String dropDatabaseSql = """
              DROP DATABASE IF EXISTS \"%s\" WITH (FORCE)
          """.formatted(isolatedDatabaseName);
      statement.execute(
          dropDatabaseSql);
    }
  }

  @Test
  @DisplayName("TaskScheduler handoff 丢失后完整应用重启只恢复并接纳一个 catch-up")
  void applicationRestart_recoversPendingMisfireAfterLostTaskSchedulerHandoff() throws Exception {
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
    final String deleteTriggerOutboxEventSql = """
        delete from batch.trigger_outbox_event where tenant_id = ?
    """;
    final String deleteWorkflowLaunchWaitSql = """
        delete from batch.workflow_launch_wait where tenant_id = ?
    """;
    final String deleteJobDependencyWaitingLaunchSql = """
        delete from batch.job_dependency_waiting_launch where tenant_id = ?
    """;
    final String deleteBatchDayWaitingLaunchSql = """
        delete from batch.batch_day_waiting_launch where tenant_id = ?
    """;
    final String deleteScheduledOccurrenceCatchUpAuditSql = """
        delete from batch.scheduled_occurrence_catch_up_audit where tenant_id = ?
    """;
    final String deleteExpectedWorkOccurrenceSql = """
        delete from batch.expected_work_occurrence where tenant_id = ?
    """;
    final String deleteScheduledOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id = ?
    """;
    final String deleteTriggerMisfirePendingSql = """
        delete from batch.trigger_misfire_pending where tenant_id = ?
    """;
    final String deleteScheduledOccurrenceCoverageSql = """
        delete from batch.scheduled_occurrence_coverage where tenant_id = ?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ?
    """;
    final String deleteBatchDayCatchupControlEventSql = """
        delete from batch.batch_day_catchup_control_event where tenant_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ?
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

    String tenantId = "misfire-restart-" + UUID.randomUUID();
    String workflowCode = "WF_" + UUID.randomUUID().toString().replace("-", "");
    Instant originalFireTime =
        Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MINUTES);
    ConfigurableApplicationContext firstProcess = null;
    ConfigurableApplicationContext restartedProcess = null;
    try {
      firstProcess = startApplication();
      JdbcTemplate firstJdbc = firstProcess.getBean(JdbcTemplate.class);
      Scheduler firstScheduler = firstProcess.getBean(Scheduler.class);
      firstJdbc.update(
          insertTenantSql,
          tenantId,
          tenantId);
      firstJdbc.update(insertBusinessCalendarSql, tenantId, LocalTime.MIDNIGHT);
      final Long workflowDefinitionId =
          firstJdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, workflowCode);

      JobKey jobKey =
          JobKey.jobKey(tenantId + ":WF:" + workflowCode, TriggerSchedulerFacade.JOB_GROUP);
      JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
          .withIdentity(jobKey)
          .usingJobData(workflowJobData(tenantId, workflowDefinitionId, workflowCode))
          .storeDurably()
          .build();
      firstScheduler.addJob(job, true);
      firstJdbc.update(
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
      when(missedTrigger.getJobDataMap())
          .thenReturn(misfireIdentityData(tenantId, workflowDefinitionId));
      when(missedTrigger.getNextFireTime()).thenReturn(Date.from(originalFireTime));
      new QuartzMisfireRecoveryListener(
              () -> firstScheduler,
              lostHandoffScheduler,
              firstProcess.getBean(ScheduledOccurrenceRecorder.class),
              new TriggerRuntimeProperties())
          .triggerMisfired(missedTrigger);
      assertThat(occurrenceStatus(firstJdbc, tenantId, workflowDefinitionId, originalFireTime))
          .isEqualTo("MISFIRE_PENDING");

      // Worker 取消继续采用现有的失败分区契约。显式的
      // 管理员分区取消由 InstanceManagement 处理。
      firstProcess.close();
      firstProcess = null;
      restartedProcess = startApplication();
      JdbcTemplate restartedJdbc = restartedProcess.getBean(JdbcTemplate.class);
      Scheduler restartedScheduler = restartedProcess.getBean(Scheduler.class);
      assertThat(restartedScheduler.checkExists(jobKey)).isTrue();

      await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> {
        Integer requestCount = restartedJdbc.queryForObject(
            selectCountSql,
            Integer.class,
            tenantId,
            workflowDefinitionId);
        assertThat(requestCount).isEqualTo(1);
        assertThat(
                occurrenceStatus(restartedJdbc, tenantId, workflowDefinitionId, originalFireTime))
            .isEqualTo("REQUESTED");
      });
    } finally {
      ConfigurableApplicationContext cleanupContext =
          restartedProcess != null ? restartedProcess : firstProcess;
      if (cleanupContext != null) {
        try {
          JdbcTemplate jdbc = cleanupContext.getBean(JdbcTemplate.class);
          Scheduler scheduler = cleanupContext.getBean(Scheduler.class);
          if (tenantId != null) {
            JobKey key =
                JobKey.jobKey(tenantId + ":WF:" + workflowCode, TriggerSchedulerFacade.JOB_GROUP);
            if (!scheduler.isShutdown() && scheduler.checkExists(key)) {
              scheduler.deleteJob(key);
            }
            jdbc.update(deleteTriggerOutboxEventSql, tenantId);
            jdbc.update(deleteWorkflowLaunchWaitSql, tenantId);
            jdbc.update(
                deleteJobDependencyWaitingLaunchSql, tenantId);
            jdbc.update(deleteBatchDayWaitingLaunchSql, tenantId);
            jdbc.update(
                deleteScheduledOccurrenceCatchUpAuditSql,
                tenantId);
            jdbc.update(deleteExpectedWorkOccurrenceSql, tenantId);
            jdbc.update(deleteScheduledOccurrenceSql, tenantId);
            jdbc.update(deleteTriggerMisfirePendingSql, tenantId);
            jdbc.update(
                deleteScheduledOccurrenceCoverageSql, tenantId);
            jdbc.update(deleteTriggerRequestSql, tenantId);
            jdbc.update(
                deleteBatchDayCatchupControlEventSql, tenantId);
            jdbc.update(deleteWorkflowDefinitionSql, tenantId);
            jdbc.update(
                deleteBatchDayCatchupControlSql, tenantId);
            jdbc.update(deleteBusinessCalendarSql, tenantId);
            jdbc.update(deleteTenantSql, tenantId);
          }
        } finally {
          cleanupContext.close();
        }
      }
    }
  }

  @Test
  @DisplayName("完整 Trigger 应用上下文重启后从持久 coverage 断点继续超容量 occurrence 追平")
  void applicationRestart_resumesBoundedOccurrenceCoverageFromPostgreSQL() throws Exception {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertBusinessCalendarSql = """
          insert into batch.business_calendar (
              tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
              catch_up_policy, catch_up_max_days, cutoff_time, late_arrival_tolerance_min,
              sla_offset_min, enabled
          ) values (?, ?, 'Coverage restart calendar', 'UTC', 'SKIP', 'AUTO', 3,
                    ?, 30, 120, true)
          """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
              schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled, calendar_code
          ) values (?, ?, ?, 'DAG', 1, true, 'CRON', '0 * * * * ?', 'UTC', 'SCHEDULED', false, ?)
          returning id
          """;
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\
        """;
    final String selectCoverageThroughAtSql = """
        select coverage_through_at from batch.scheduled_occurrence_coverage where tenant_id = ?\s\
        and workflow_definition_id = ?\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\
        """;
    final String selectCountSql3 = """
        select count(*) from (select scheduled_fire_time, lag(scheduled_fire_time) over (order by\s\
        scheduled_fire_time) as previous_fire_time from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ?) fires where previous_fire_time is not null\s\
        and scheduled_fire_time <> previous_fire_time + interval '1 second'\
        """;

    String tenantId = "coverage-restart-" + UUID.randomUUID();
    String workflowCode = "WF_" + UUID.randomUUID().toString().replace("-", "");
    String calendarCode = "coverage_restart_cal";
    Instant scanAt;
    Instant firstFireTime;
    Instant requestedThrough;
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setTimezone("UTC");
    descriptor.setCalendarCode(calendarCode);
    Trigger denseTrigger;
    ConfigurableApplicationContext firstProcess = null;
    ConfigurableApplicationContext restartedProcess = null;
    try {
      firstProcess = startApplication();
      JdbcTemplate firstJdbc = firstProcess.getBean(JdbcTemplate.class);
      firstJdbc.update(
          insertTenantSql,
          tenantId,
          tenantId);
      firstJdbc.update(insertBusinessCalendarSql, tenantId, calendarCode, LocalTime.MIDNIGHT);
      Long workflowDefinitionId = firstJdbc.queryForObject(
          insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, workflowCode, calendarCode);
      descriptor.setWorkflowDefinitionId(workflowDefinitionId);

      scanAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      firstFireTime = scanAt.plusSeconds(1);
      requestedThrough = scanAt.plus(Duration.ofDays(2));
      denseTrigger = TriggerBuilder.newTrigger()
          .withIdentity("coverage-restart-dense", "coverage-restart")
          .startAt(Date.from(firstFireTime))
          .withSchedule(
              SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(1).repeatForever())
          .build();

      TriggerScheduledOccurrencePlanner firstPlanner =
          firstProcess.getBean(TriggerScheduledOccurrencePlanner.class);
      int firstPass = firstPlanner.materialize(descriptor, denseTrigger, scanAt);
      assertThat(firstPass).isBetween(80_000, 99_999);
      long firstOccurrenceCount = firstJdbc.queryForObject(
          selectCountSql,
          Long.class,
          tenantId,
          workflowDefinitionId);
      assertThat(firstOccurrenceCount).isEqualTo(firstPass);
      Instant partialCoverage = firstJdbc.queryForObject(
          selectCoverageThroughAtSql,
          (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant(),
          tenantId,
          workflowDefinitionId);
      assertThat(partialCoverage).isBefore(requestedThrough);

      firstProcess.close();
      firstProcess = null;
      restartedProcess = startApplication();
      JdbcTemplate restartedJdbc = restartedProcess.getBean(JdbcTemplate.class);
      Instant persistedCoverageAfterRestart = restartedJdbc.<Instant>queryForObject(
          selectCoverageThroughAtSql,
          (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant(),
          tenantId,
          workflowDefinitionId);
      assertThat(persistedCoverageAfterRestart).isEqualTo(partialCoverage);
      Long selectedScheduledOccurrenceCount =
          restartedJdbc.queryForObject(selectCountSql2, Long.class, tenantId, workflowDefinitionId);
      assertThat(selectedScheduledOccurrenceCount).isEqualTo(firstOccurrenceCount);

      TriggerScheduledOccurrencePlanner restartedPlanner =
          restartedProcess.getBean(TriggerScheduledOccurrencePlanner.class);
      int resumedCount = restartedPlanner.materialize(descriptor, denseTrigger, scanAt);
      long expectedOccurrenceCount =
          Duration.between(firstFireTime, requestedThrough).getSeconds() + 1;
      assertThat(resumedCount).isPositive();
      Instant coverageAfterResume = restartedJdbc.<Instant>queryForObject(
          selectCoverageThroughAtSql,
          (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant(),
          tenantId,
          workflowDefinitionId);
      assertThat(coverageAfterResume).isEqualTo(requestedThrough);
      Long selectedScheduledOccurrenceCountRead2 =
          restartedJdbc.queryForObject(selectCountSql2, Long.class, tenantId, workflowDefinitionId);
      assertThat(selectedScheduledOccurrenceCountRead2).isEqualTo(expectedOccurrenceCount);
      Integer selectedCount = restartedJdbc.queryForObject(
          selectCountSql3, Integer.class, tenantId, workflowDefinitionId);
      assertThat(selectedCount).isZero();
    } finally {
      if (restartedProcess != null) {
        restartedProcess.close();
      }
      if (firstProcess != null) {
        firstProcess.close();
      }
    }
  }

  @Test
  @DisplayName("planner 写入 occurrence 后 JVM 强杀会整体回滚并从同一窗口无缺口恢复")
  void processCrashAfterOccurrenceWrites_rollsBackAndPlannerResumesWithoutGaps() throws Exception {
    final String selectRevisionSql = """
        select revision from batch.tenant_configuration_revision where tenant_id = ? for update\
        """;
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String selectCountSql3 = """
                    select count(*)
                      from pg_stat_activity activity
                     where activity.application_name = 'occurrence-planner-crash'
                       and activity.state = 'active'
                       and activity.wait_event_type = 'Lock'
                       and ? = any(pg_blocking_pids(activity.pid))
                       and activity.query ilike '%lock_tenant_configuration_revision%'
                    """;

    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (
                tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
                catch_up_policy, catch_up_max_days, cutoff_time, late_arrival_tolerance_min,
                sla_offset_min, enabled
            ) values (?, ?, 'Coverage crash calendar', 'UTC', 'SKIP', 'AUTO', 3,
                      ?, 30, 120, true)
            """;
    final String insertWorkflowDefinitionSql = """
            insert into batch.workflow_definition (
                tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
                schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled, calendar_code
            ) values (?, ?, ?, 'DAG', 1, true, 'CRON', '0 0/1 * * * ?', 'UTC', 'SCHEDULED', false, ?)
            returning id
            """;
    final String selectCurrentTenantConfigurationRevisionSql = """
        select batch.current_tenant_configuration_revision(?)
    """;
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\
        """;
    final String selectCoverageThroughAtSql = """
        select coverage_through_at from batch.scheduled_occurrence_coverage where tenant_id = ?\s\
        and workflow_definition_id = ?\
        """;
    final String selectCountSql2 = """
        select count(*) from (select scheduled_fire_time, lag(scheduled_fire_time) over (order by\s\
        scheduled_fire_time) as previous_fire_time from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ?) fires where previous_fire_time is not null\s\
        and scheduled_fire_time <> previous_fire_time + interval '60 seconds'\
        """;

    String tenantId = "coverage-crash-" + UUID.randomUUID();
    String workflowCode = "WF_" + UUID.randomUUID().toString().replace("-", "");
    String calendarCode = "coverage_crash_cal";
    Instant scanAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Instant firstFireTime = scanAt.plusSeconds(60);
    Instant requestedThrough = scanAt.plus(Duration.ofDays(2));
    Path temporaryDirectory = Files.createTempDirectory("occurrence-planner-crash-");
    Path propertiesPath = temporaryDirectory.resolve("application.properties");
    Path readyMarker = temporaryDirectory.resolve("ready");
    Path childLog = temporaryDirectory.resolve("child.log");
    ConfigurableApplicationContext recoveryContext = null;
    Connection blockerConnection = null;
    Process childProcess = null;
    long workflowDefinitionId;
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setTimezone("UTC");
    descriptor.setCalendarCode(calendarCode);

    try {
      try (ConfigurableApplicationContext seedContext = startApplication()) {
        JdbcTemplate jdbc = seedContext.getBean(JdbcTemplate.class);
        jdbc.update(
            insertTenantSql,
            tenantId,
            tenantId);
        jdbc.update(insertBusinessCalendarSql, tenantId, calendarCode, LocalTime.MIDNIGHT);
        workflowDefinitionId = jdbc.queryForObject(
            insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, workflowCode, calendarCode);
        descriptor.setWorkflowDefinitionId(workflowDefinitionId);
        jdbc.queryForObject(
            selectCurrentTenantConfigurationRevisionSql, Long.class, tenantId);
      }

      blockerConnection = DriverManager.getConnection(
          isolatedJdbcUrl, platformJdbcUsername(), platformJdbcPassword());
      blockerConnection.setAutoCommit(false);
      int blockerPid;
      try (var statement = blockerConnection.prepareStatement(
              selectRevisionSql);
          var pidStatement = blockerConnection.prepareStatement(selectPgBackendPidSql)) {
        statement.setString(1, tenantId);
        try (var resultSet = statement.executeQuery()) {
          assertThat(resultSet.next()).isTrue();
        }
        try (var resultSet = pidStatement.executeQuery()) {
          assertThat(resultSet.next()).isTrue();
          blockerPid = resultSet.getInt(1);
        }
      }

      Map<String, Object> childProperties = new HashMap<>(applicationProperties());
      childProperties.put(
          "spring.datasource.url",
          jdbcUrlWithApplicationName(isolatedJdbcUrl, "occurrence-planner-crash"));
      childProperties.put("spring.quartz.scheduler-name", isolatedDatabaseName + "_crash_child");
      writeProperties(childProperties, propertiesPath);
      String classpath =
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      assertThat(classpath).isNotBlank();
      String javaExecutable =
          Path.of(System.getProperty("java.home"), "bin", "java").toString();
      childProcess = new ProcessBuilder(
              javaExecutable,
              "-cp",
              classpath,
              OccurrencePlannerCrashProcess.class.getName(),
              propertiesPath.toString(),
              readyMarker.toString(),
              scanAt.toString(),
              firstFireTime.toString(),
              tenantId,
              Long.toString(workflowDefinitionId),
              workflowCode,
              calendarCode)
          .redirectErrorStream(true)
          .redirectOutput(childLog.toFile())
          .start();

      Process runningChild = childProcess;
      await()
          .atMost(Duration.ofSeconds(90))
          .until(() -> Files.exists(readyMarker) || !runningChild.isAlive());
      assertThat(childProcess.isAlive())
          .as("child application should start; log: %s", Files.readString(childLog))
          .isTrue();

      await()
          .pollInterval(Duration.ofMillis(100))
          .atMost(Duration.ofSeconds(90))
          .untilAsserted(() -> {
            try (var observer = DriverManager.getConnection(
                    isolatedJdbcUrl, platformJdbcUsername(), platformJdbcPassword());
                var statement = observer.prepareStatement(selectCountSql3)) {
              statement.setInt(1, blockerPid);
              try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1))
                    .as(
                        "production planner reaches its final configuration fence after occurrence writes")
                    .isPositive();
              }
            }
          });

      assertThat(countRows(
              isolatedJdbcUrl,
              SELECT_SCHEDULED_OCCURRENCE_COUNT_BY_TENANT_SQL,
              tenantId))
          .as("the child transaction's occurrence rows must remain uncommitted")
          .isZero();
      assertThat(countRows(
              isolatedJdbcUrl,
              SELECT_SCHEDULED_OCCURRENCE_COVERAGE_COUNT_BY_TENANT_SQL,
              tenantId))
          .as("coverage must not be published before the planner transaction commits")
          .isZero();

      childProcess.destroyForcibly();
      assertThat(childProcess.waitFor(15, TimeUnit.SECONDS))
          .as("the planner JVM must be terminated before releasing its database fence")
          .isTrue();
      childProcess = null;
      assertThat(countRows(
              isolatedJdbcUrl,
              SELECT_SCHEDULED_OCCURRENCE_COUNT_BY_TENANT_SQL,
              tenantId))
          .isZero();
      assertThat(countRows(
              isolatedJdbcUrl,
              SELECT_SCHEDULED_OCCURRENCE_COVERAGE_COUNT_BY_TENANT_SQL,
              tenantId))
          .isZero();

      blockerConnection.close();
      blockerConnection = null;
      recoveryContext = startApplication();
      JdbcTemplate recoveryJdbc = recoveryContext.getBean(JdbcTemplate.class);
      Trigger recoveryTrigger = TriggerBuilder.newTrigger()
          .withIdentity(workflowCode, "crash-process-recovery")
          .startAt(Date.from(firstFireTime))
          .withSchedule(
              SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).repeatForever())
          .build();
      int recovered = recoveryContext
          .getBean(TriggerScheduledOccurrencePlanner.class)
          .materialize(descriptor, recoveryTrigger, scanAt);
      long expectedOccurrences =
          Duration.between(firstFireTime, requestedThrough).toSeconds() / 60 + 1;
      assertThat(recovered).isEqualTo(expectedOccurrences);
      Long selectedScheduledOccurrenceCount =
          recoveryJdbc.queryForObject(selectCountSql, Long.class, tenantId, workflowDefinitionId);
      assertThat(selectedScheduledOccurrenceCount).isEqualTo(expectedOccurrences);
      Instant selectedCoverageThroughAt = recoveryJdbc.<Instant>queryForObject(
          selectCoverageThroughAtSql,
          (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant(),
          tenantId,
          workflowDefinitionId);
      assertThat(selectedCoverageThroughAt).isEqualTo(requestedThrough);
      Integer selectedCount =
          recoveryJdbc.queryForObject(selectCountSql2, Integer.class, tenantId, workflowDefinitionId);
      assertThat(selectedCount).isZero();
    } finally {
      if (childProcess != null && childProcess.isAlive()) {
        childProcess.destroyForcibly();
        childProcess.waitFor(15, TimeUnit.SECONDS);
      }
      if (blockerConnection != null) {
        blockerConnection.close();
      }
      if (recoveryContext != null) {
        recoveryContext.close();
      }
      Files.deleteIfExists(childLog);
      Files.deleteIfExists(readyMarker);
      Files.deleteIfExists(propertiesPath);
      Files.deleteIfExists(temporaryDirectory);
    }
  }

  @Test
  @DisplayName("完整应用重启后持久 Quartz readiness retry 保留原始 fire 并恢复接纳")
  void applicationRestart_recoversPersistedReadinessRetryWithOriginalFireIdentity()
      throws Exception {
        final String insertTenantSql = """
            insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
        """;
        final String insertBusinessCalendarSql = """
          insert into batch.business_calendar (
              tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
              catch_up_policy, catch_up_max_days, cutoff_time, late_arrival_tolerance_min,
              sla_offset_min, enabled
          ) values (?, ?, 'Readiness calendar', 'UTC', 'SKIP', 'AUTO', 3,
                    ?, 30, 120, true)
          """;
        final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
              schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled,
              calendar_code, depends_on_job_code
          ) values (?, ?, ?, 'DAG', 1, true, 'CRON', '0 0 * * * ?', 'UTC',
                    'SCHEDULED', true, ?, ?) returning id
          """;
        final String selectCountSql = """
            select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
            and scheduled_fire_time = ?\
            """;
        final String selectCountSql2 = """
            select count(*) from quartz.qrtz_triggers where sched_name = ? and job_name = ?\s\
            and trigger_name like 'readiness-retry-%%'\
            """;
        final String selectCountSql3 = """
            select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
            and biz_date = ? and trigger_type = 'SCHEDULED'\
            """;
        final String selectLaunchPayloadSql = """
            select launch_payload -> 'params' ->> 'scheduledAt' from batch.trigger_request where tenant_id = ?\s\
            and workflow_definition_id = ? and biz_date = ? and trigger_type = 'SCHEDULED'\
            """;
        final String deleteTriggerOutboxEventSql = """
            delete from batch.trigger_outbox_event where tenant_id = ?
        """;
        final String deleteWorkflowLaunchWaitSql = """
            delete from batch.workflow_launch_wait where tenant_id = ?
        """;
        final String deleteJobDependencyWaitingLaunchSql = """
            delete from batch.job_dependency_waiting_launch where tenant_id = ?
        """;
        final String deleteBatchDayWaitingLaunchSql = """
            delete from batch.batch_day_waiting_launch where tenant_id = ?
        """;
        final String deleteScheduledOccurrenceCatchUpAuditSql = """
            delete from batch.scheduled_occurrence_catch_up_audit where tenant_id = ?
        """;
        final String deleteExpectedWorkOccurrenceSql = """
            delete from batch.expected_work_occurrence where tenant_id = ?
        """;
        final String deleteScheduledOccurrenceSql = """
            delete from batch.scheduled_occurrence where tenant_id = ?
        """;
        final String deleteTriggerMisfirePendingSql = """
            delete from batch.trigger_misfire_pending where tenant_id = ?
        """;
        final String deleteScheduledOccurrenceCoverageSql = """
            delete from batch.scheduled_occurrence_coverage where tenant_id = ?
        """;
        final String deleteTriggerRequestSql = """
            delete from batch.trigger_request where tenant_id = ?
        """;
        final String deleteBatchDayCatchupControlEventSql = """
            delete from batch.batch_day_catchup_control_event where tenant_id = ?
        """;
        final String deleteBatchDayCatchupControlSql = """
            delete from batch.batch_day_catchup_control where tenant_id = ?
        """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = ?
    """;
    final String deleteTenantSql = """
        delete from batch.tenant where tenant_id = ?
    """;

    String tenantId = "readiness-restart-" + UUID.randomUUID();
    String workflowCode = "WF_" + UUID.randomUUID().toString().replace("-", "");
    String calendarCode = "readiness_cal";
    String upstreamJobCode = "UPSTREAM_" + UUID.randomUUID().toString().replace("-", "");
    Instant scheduledFireTime;
    LocalDate bizDate;
    AtomicInteger readinessChecks = new AtomicInteger();
    MockWebServer readinessServer = new MockWebServer();
    readinessServer.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        if (request.getUrl() == null
            || !request.getUrl().encodedPath().equals("/internal/readiness/job")) {
          return new MockResponse.Builder().code(404).build();
        }
        int check = readinessChecks.incrementAndGet();
        String body = check == 1
            ? "{\"ready\":false,\"reason\":\"upstream pending\",\"readyAt\":null}"
            : "{\"ready\":true,\"reason\":null,\"readyAt\":\"" + Instant.now() + "\"}";
        return new MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build();
      }
    });
    readinessServer.start(InetAddress.getByName("127.0.0.1"), 0);
    orchestratorBaseUrlOverride = readinessServer.url("/").toString();
    ConfigurableApplicationContext firstProcess = null;
    ConfigurableApplicationContext restartedProcess = null;
    Long workflowDefinitionId = null;
    JobKey jobKey = null;
    try {
      firstProcess = startApplication();
      JdbcTemplate firstJdbc = firstProcess.getBean(JdbcTemplate.class);
      Scheduler firstScheduler = firstProcess.getBean(Scheduler.class);
      scheduledFireTime = Instant.now().plusSeconds(2).truncatedTo(ChronoUnit.MILLIS);
      bizDate = scheduledFireTime.atZone(ZoneOffset.UTC).toLocalDate();
      firstJdbc.update(
          insertTenantSql,
          tenantId,
          tenantId);
      firstJdbc.update(insertBusinessCalendarSql, tenantId, calendarCode, LocalTime.MIDNIGHT);
      workflowDefinitionId = firstJdbc.queryForObject(
          insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, workflowCode, calendarCode, upstreamJobCode);
      firstProcess
          .getBean(TriggerRegistrationService.class)
          .registerByWorkflowCode(tenantId, workflowCode);
      jobKey = JobKey.jobKey(tenantId + ":WF:" + workflowCode, TriggerSchedulerFacade.JOB_GROUP);
      assertThat(firstScheduler.checkExists(jobKey)).isTrue();

      Trigger firstFire = TriggerBuilder.newTrigger()
          .withIdentity("readiness-initial-" + UUID.randomUUID(), TriggerSchedulerFacade.JOB_GROUP)
          .forJob(jobKey)
          .startAt(Date.from(scheduledFireTime))
          .build();
      firstScheduler.scheduleJob(firstFire);
      Long definitionId = workflowDefinitionId;
      await()
          .pollInterval(Duration.ofMillis(100))
          .atMost(Duration.ofSeconds(15))
          .untilAsserted(() -> {
            Integer selectedScheduledOccurrenceCount = firstJdbc.queryForObject(
                selectCountSql, Integer.class, tenantId, definitionId, Timestamp.from(scheduledFireTime));
            assertThat(selectedScheduledOccurrenceCount).isOne();
            assertThat(occurrenceStatus(firstJdbc, tenantId, definitionId, scheduledFireTime))
                .isEqualTo("WAITING_READINESS");
          });
      assertThat(readinessChecks).hasValue(1);
      String schedulerName = firstScheduler.getSchedulerName();
      String quartzJobName = jobKey.getName();
      await()
          .pollInterval(Duration.ofMillis(100))
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> {
            Integer selectedQrtzTriggersCount =
                firstJdbc.queryForObject(selectCountSql2, Integer.class, schedulerName, quartzJobName);
            assertThat(selectedQrtzTriggersCount).isEqualTo(1);
          });

      firstProcess.close();
      firstProcess = null;
      restartedProcess = startApplication();
      JdbcTemplate restartedJdbc = restartedProcess.getBean(JdbcTemplate.class);
      await()
          .pollInterval(Duration.ofMillis(200))
          .atMost(Duration.ofSeconds(35))
          .untilAsserted(() -> {
            assertThat(readinessChecks).hasValue(2);
            assertThat(occurrenceStatus(restartedJdbc, tenantId, definitionId, scheduledFireTime))
                .isEqualTo("REQUESTED");
            Integer selectedTriggerRequestCount = restartedJdbc.queryForObject(
                selectCountSql3, Integer.class, tenantId, definitionId, bizDate);
            assertThat(selectedTriggerRequestCount).isEqualTo(1);
            String selectedLaunchPayload = restartedJdbc.queryForObject(
                selectLaunchPayloadSql, String.class, tenantId, definitionId, bizDate);
            assertThat(selectedLaunchPayload).isEqualTo(scheduledFireTime.toString());
          });
    } finally {
      ConfigurableApplicationContext cleanupContext =
          restartedProcess != null ? restartedProcess : firstProcess;
      if (cleanupContext != null) {
        try {
          if (jobKey != null) {
            Scheduler scheduler = cleanupContext.getBean(Scheduler.class);
            if (!scheduler.isShutdown() && scheduler.checkExists(jobKey)) {
              scheduler.deleteJob(jobKey);
            }
          }
          JdbcTemplate jdbc = cleanupContext.getBean(JdbcTemplate.class);
          if (workflowDefinitionId != null) {
            jdbc.update(deleteTriggerOutboxEventSql, tenantId);
            jdbc.update(deleteWorkflowLaunchWaitSql, tenantId);
            jdbc.update(
                deleteJobDependencyWaitingLaunchSql, tenantId);
            jdbc.update(deleteBatchDayWaitingLaunchSql, tenantId);
            jdbc.update(
                deleteScheduledOccurrenceCatchUpAuditSql,
                tenantId);
            jdbc.update(deleteExpectedWorkOccurrenceSql, tenantId);
            jdbc.update(deleteScheduledOccurrenceSql, tenantId);
            jdbc.update(deleteTriggerMisfirePendingSql, tenantId);
            jdbc.update(
                deleteScheduledOccurrenceCoverageSql, tenantId);
            jdbc.update(deleteTriggerRequestSql, tenantId);
            jdbc.update(
                deleteBatchDayCatchupControlEventSql, tenantId);
            jdbc.update(
                deleteBatchDayCatchupControlSql, tenantId);
            jdbc.update(deleteWorkflowDefinitionSql, tenantId);
            jdbc.update(deleteBusinessCalendarSql, tenantId);
            jdbc.update(deleteTenantSql, tenantId);
          }
        } finally {
          cleanupContext.close();
        }
      }
      orchestratorBaseUrlOverride = null;
      readinessServer.close();
    }
  }

  private ConfigurableApplicationContext startApplication() {
    return new SpringApplicationBuilder(BatchTriggerApplication.class)
        .environment(testEnvironment())
        .web(WebApplicationType.NONE)
        .run();
  }

  private ConfigurableEnvironment testEnvironment() {
    StandardEnvironment environment = new StandardEnvironment();
    environment.setActiveProfiles("test");
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test-containers", applicationProperties()));
    return environment;
  }

  private Map<String, Object> applicationProperties() {
    return Map.ofEntries(
        Map.entry("spring.datasource.url", isolatedJdbcUrl),
        Map.entry(
            "batch.orchestrator.base-url",
            orchestratorBaseUrlOverride == null
                ? "http://localhost:18082"
                : orchestratorBaseUrlOverride),
        Map.entry("spring.quartz.scheduler-name", isolatedDatabaseName),
        // 原子应用超时回退，并推进所属 Workflow DAG。
        // 持久化当前租约所有者即将运行非幂等执行器这一事实。
        Map.entry("batch.shedlock.redis.key-prefix-env", isolatedDatabaseName),
        Map.entry("spring.datasource.username", platformJdbcUsername()),
        Map.entry("spring.datasource.password", platformJdbcPassword()),
        Map.entry("spring.datasource.driver-class-name", "org.postgresql.Driver"),
        Map.entry("spring.datasource.hikari.maximum-pool-size", "20"),
        Map.entry("spring.datasource.hikari.minimum-idle", "1"),
        Map.entry("spring.flyway.enabled", "true"),
        Map.entry("spring.flyway.default-schema", "batch"),
        Map.entry("spring.flyway.schemas", "batch,quartz"),
        Map.entry("spring.flyway.postgresql.transactional-lock", "false"),
        Map.entry("batch.datasource.pg-session.platform.idle-in-transaction-timeout", "10m"),
        Map.entry("batch.datasource.business.url", businessJdbcUrl()),
        Map.entry("batch.datasource.business.username", businessJdbcUsername()),
        Map.entry("batch.datasource.business.password", businessJdbcPassword()),
        Map.entry("batch.datasource.business.schema", "biz"),
        Map.entry("spring.kafka.bootstrap-servers", kafkaBootstrapServers()),
        Map.entry("spring.data.redis.host", redisHost()),
        Map.entry("spring.data.redis.port", redisPort()),
        Map.entry("batch.storage.backend", "s3"),
        Map.entry("batch.storage.s3.endpoint", s3Endpoint()),
        Map.entry("batch.storage.s3.access-key", s3AccessKey()),
        Map.entry("batch.storage.s3.secret-key", s3SecretKey()),
        Map.entry("batch.storage.s3.bucket", s3Bucket()),
        Map.entry("batch.storage.s3.region", s3Region()),
        Map.entry("batch.storage.s3.path-style-enabled", s3PathStyleEnabled()),
        Map.entry("batch.security.bypass-mode", "true"),
        Map.entry("batch.security.kms.default-key-ref", "DEFAULT_TEST"),
        Map.entry("batch.security.kms.keys.DEFAULT_TEST", "AAAAAAAAAAAAAAAAAAAAAA=="),
        Map.entry("batch.worker.drain.heartbeat-timeout-scheduler-enabled", "false"),
        Map.entry("batch.partition-lease.reclaim-scheduler-enabled", "false"),
        Map.entry("spring.quartz.job-store-type", "jdbc"),
        Map.entry("spring.quartz.jdbc.initialize-schema", "never"),
        Map.entry("spring.quartz.properties.org.quartz.jobStore.misfireThreshold", "1000"),
        Map.entry("batch.trigger.runtime.readiness-recheck-interval-seconds", "5"),
        Map.entry("batch.trigger.runtime.readiness-window-seconds", "120"));
  }

  private static String jdbcUrlWithApplicationName(String jdbcUrl, String applicationName) {
    return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "ApplicationName=" + applicationName;
  }

  private static void writeProperties(Map<String, Object> values, Path target) throws IOException {
    Properties properties = new Properties();
    values.forEach((key, value) -> properties.setProperty(key, String.valueOf(value)));
    try (var output = Files.newOutputStream(target)) {
      properties.store(output, "temporary child JVM integration-test configuration");
    }
  }

  private static long countRows(String jdbcUrl, String sql, String tenantId) throws Exception {
    try (var connection =
            DriverManager.getConnection(jdbcUrl, platformJdbcUsername(), platformJdbcPassword());
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenantId);
      try (var resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return resultSet.getLong(1);
      }
    }
  }

  private static String occurrenceStatus(
      JdbcTemplate jdbc, String tenantId, Long workflowDefinitionId, Instant scheduledFireTime) {
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;

    return jdbc.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(scheduledFireTime));
  }

  private static JobDataMap workflowJobData(
      String tenantId, Long workflowDefinitionId, String workflowCode) {
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

  private static JobDataMap misfireIdentityData(String tenantId, Long workflowDefinitionId) {
    JobDataMap data = new JobDataMap();
    data.put(QuartzLaunchJob.TENANT_ID, tenantId);
    data.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, workflowDefinitionId);
    return data;
  }
}
