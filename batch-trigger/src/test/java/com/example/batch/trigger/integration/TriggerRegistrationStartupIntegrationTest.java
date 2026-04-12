package com.example.batch.trigger.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.domain.TriggerRegistrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration"
    })
@Import(TriggerRegistrationStartupIntegrationTest.TestConfig.class)
class TriggerRegistrationStartupIntegrationTest extends AbstractIntegrationTest {

  @Autowired JdbcTemplate jdbcTemplate;

  @Autowired TriggerRegistrationService triggerRegistrationService;

  @Autowired Scheduler scheduler;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from batch.job_definition where tenant_id = ?", "t-trigger");
    reset(scheduler); // Spring singleton mock — 每个测试前重置调用计数
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from batch.job_definition where tenant_id = ?", "t-trigger");
  }

  @Test
  void shouldRegisterAllEnabledCronDefinitionsIntoQuartz() throws Exception {
    insertCronJobDefinition("t-trigger", "JOB_A", "0 0/5 * * * ?");
    insertCronJobDefinition("t-trigger", "JOB_B", "0 10 * * * ?");
    insertManualJobDefinition("t-trigger", "JOB_MANUAL");

    when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);
    triggerRegistrationService.registerAll();

    verify(scheduler, times(2)).scheduleJob(any(), any());
  }

  @Test
  void shouldRegisterFixedRateDefinitionsAlongWithCron() throws Exception {
    insertCronJobDefinition("t-trigger", "JOB_CRON", "0 0/5 * * * ?");
    insertFixedRateJobDefinition("t-trigger", "JOB_FR", "120");
    insertManualJobDefinition("t-trigger", "JOB_MANUAL");

    when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);
    triggerRegistrationService.registerAll();

    // CRON + FIXED_RATE の 2 件、MANUAL はスキップ
    verify(scheduler, times(2)).scheduleJob(any(), any());
  }

  private void insertCronJobDefinition(String tenantId, String jobCode, String cronExpr) {
    jdbcTemplate.update(
        """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type,
            schedule_type, schedule_expr, timezone, trigger_mode,
            priority, enabled
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tenantId,
        jobCode,
        jobCode,
        "GENERAL",
        "CRON",
        cronExpr,
        "UTC",
        "SCHEDULED",
        5,
        true);
  }

  private void insertFixedRateJobDefinition(
      String tenantId, String jobCode, String intervalSeconds) {
    jdbcTemplate.update(
        """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type,
            schedule_type, schedule_expr, timezone, trigger_mode,
            priority, enabled
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tenantId,
        jobCode,
        jobCode,
        "GENERAL",
        "FIXED_RATE",
        intervalSeconds,
        "UTC",
        "SCHEDULED",
        5,
        true);
  }

  private void insertManualJobDefinition(String tenantId, String jobCode) {
    jdbcTemplate.update(
        """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type,
            schedule_type, timezone, trigger_mode,
            priority, enabled
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tenantId,
        jobCode,
        jobCode,
        "GENERAL",
        "MANUAL",
        "UTC",
        "SCHEDULED",
        5,
        true);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    Scheduler scheduler() {
      return mock(Scheduler.class);
    }
  }
}
