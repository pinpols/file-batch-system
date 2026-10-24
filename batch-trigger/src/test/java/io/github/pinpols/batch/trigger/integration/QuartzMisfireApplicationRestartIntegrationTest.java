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
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;

/** Boots, stops, and reboots the complete Trigger application against the same PostgreSQL JobStore. */
@DisplayName("Trigger 完整应用上下文重启:从 PostgreSQL 恢复持久 misfire")
class QuartzMisfireApplicationRestartIntegrationTest extends AbstractIntegrationTest {

  private String isolatedDatabaseName;
  private String isolatedJdbcUrl;

  @org.junit.jupiter.api.BeforeEach
  void isolatePlatformDatabaseFromCachedSchedulerContexts() throws Exception {
    isolatedDatabaseName = "quartz_restart_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = java.sql.DriverManager.getConnection(
            platformJdbcUrl(), platformJdbcUsername(), platformJdbcPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE \"%s\"".formatted(isolatedDatabaseName));
    }
    String sharedUrl = platformJdbcUrl();
    int databaseStart = sharedUrl.indexOf('/', "jdbc:postgresql://".length());
    int queryStart = sharedUrl.indexOf('?', databaseStart);
    isolatedJdbcUrl = sharedUrl.substring(0, databaseStart + 1)
        + isolatedDatabaseName
        + (queryStart < 0 ? "" : sharedUrl.substring(queryStart));
    try (var connection = java.sql.DriverManager.getConnection(
        isolatedJdbcUrl, platformJdbcUsername(), platformJdbcPassword())) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
          connection, new org.springframework.core.io.ClassPathResource("db/platform-init.sql"));
    }
  }

  @org.junit.jupiter.api.AfterEach
  void dropIsolatedPlatformDatabase() throws Exception {
    if (io.github.pinpols.batch.common.utils.EmptyChecks.isBlank(isolatedDatabaseName)) {
      return;
    }
    try (var connection = java.sql.DriverManager.getConnection(
            platformJdbcUrl(), platformJdbcUsername(), platformJdbcPassword());
        var statement = connection.createStatement()) {
      statement.execute(
          "DROP DATABASE IF EXISTS \"%s\" WITH (FORCE)".formatted(isolatedDatabaseName));
    }
  }

  @Test
  @DisplayName("TaskScheduler handoff 丢失后完整应用重启只恢复并接纳一个 catch-up")
  void applicationRestart_recoversPendingMisfireAfterLostTaskSchedulerHandoff() throws Exception {
    String tenantId = "misfire-restart-" + UUID.randomUUID();
    String workflowCode = "WF_" + UUID.randomUUID().toString().replace("-", "");
    Instant originalFireTime =
        Instant.now().minus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    ConfigurableApplicationContext firstProcess = null;
    ConfigurableApplicationContext restartedProcess = null;
    try {
      firstProcess = startApplication();
      JdbcTemplate firstJdbc = firstProcess.getBean(JdbcTemplate.class);
      Scheduler firstScheduler = firstProcess.getBean(Scheduler.class);
      firstJdbc.update(
          "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')",
          tenantId,
          tenantId);
      firstJdbc.update("""
          insert into batch.business_calendar (
              tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
              catch_up_policy, catch_up_max_days, cutoff_time, late_arrival_tolerance_min,
              sla_offset_min, enabled
          ) values (?, 'misfire_cal', 'Misfire calendar', 'UTC', 'SKIP', 'AUTO', 3,
                    ?, 30, 120, true)
          """, tenantId, LocalTime.MIDNIGHT);
      final Long workflowDefinitionId =
          firstJdbc.queryForObject("""
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
              schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled, calendar_code
          ) values (?, ?, ?, 'DAG', 1, true, 'CRON', '0 0 * * * ?', 'UTC', 'SCHEDULED', true,
                    'misfire_cal') returning id
          """, Long.class, tenantId, workflowCode, workflowCode);

      JobKey jobKey =
          JobKey.jobKey(tenantId + ":WF:" + workflowCode, TriggerSchedulerFacade.JOB_GROUP);
      JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
          .withIdentity(jobKey)
          .usingJobData(workflowJobData(tenantId, workflowDefinitionId, workflowCode))
          .storeDurably()
          .build();
      firstScheduler.addJob(job, true);
      firstJdbc.update(
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
      when(missedTrigger.getJobDataMap())
          .thenReturn(misfireIdentityData(tenantId, workflowDefinitionId));
      when(missedTrigger.getNextFireTime()).thenReturn(Date.from(originalFireTime));
      new QuartzMisfireRecoveryListener(
              () -> firstScheduler,
              lostHandoffScheduler,
              firstProcess.getBean(ScheduledOccurrenceRecorder.class))
          .triggerMisfired(missedTrigger);
      assertThat(occurrenceStatus(firstJdbc, tenantId, workflowDefinitionId, originalFireTime))
          .isEqualTo("MISFIRE_PENDING");

      // The pending callback exists only in the old process's TaskScheduler. Closing this context
      // destroys that executor and Quartz instance while PostgreSQL retains both durable records.
      firstProcess.close();
      firstProcess = null;
      restartedProcess = startApplication();
      JdbcTemplate restartedJdbc = restartedProcess.getBean(JdbcTemplate.class);
      Scheduler restartedScheduler = restartedProcess.getBean(Scheduler.class);
      assertThat(restartedScheduler.checkExists(jobKey)).isTrue();

      await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> {
        Integer requestCount = restartedJdbc.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ? "
                + "and workflow_definition_id = ? and trigger_type = 'CATCH_UP'",
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
            jdbc.update("delete from batch.trigger_outbox_event where tenant_id = ?", tenantId);
            jdbc.update("delete from batch.trigger_request where tenant_id = ?", tenantId);
            jdbc.update("delete from batch.scheduled_occurrence where tenant_id = ?", tenantId);
            jdbc.update(
                "delete from batch.scheduled_occurrence_coverage where tenant_id = ?", tenantId);
            jdbc.update("delete from batch.workflow_definition where tenant_id = ?", tenantId);
            jdbc.update("delete from batch.business_calendar where tenant_id = ?", tenantId);
            jdbc.update("delete from batch.tenant where tenant_id = ?", tenantId);
          }
        } finally {
          cleanupContext.close();
        }
      }
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
        Map.entry("spring.quartz.scheduler-name", isolatedDatabaseName),
        // Other cached Spring test contexts share Redis, including contexts with a mock Scheduler.
        // Keep the same lock namespace across this restart while isolating it from those contexts.
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
        Map.entry("spring.quartz.properties.org.quartz.jobStore.misfireThreshold", "1000"));
  }

  private static String occurrenceStatus(
      JdbcTemplate jdbc, String tenantId, Long workflowDefinitionId, Instant scheduledFireTime) {
    return jdbc.queryForObject(
        "select occurrence_status from batch.scheduled_occurrence where tenant_id = ? "
            + "and workflow_definition_id = ? and scheduled_fire_time = ?",
        String.class,
        tenantId,
        workflowDefinitionId,
        java.sql.Timestamp.from(scheduledFireTime));
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
