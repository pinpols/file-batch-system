package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.infrastructure.QuartzLaunchJob;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
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
      "spring.autoconfigure.exclude=org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration",
      // 关 QuartzMetricsConfiguration：本测试用 mock(Scheduler) 而非真 Quartz，
      // 否则 mock 的 getListenerManager() 返回 null 在 @PostConstruct 触发 NPE
      "batch.trigger.quartz-metrics.enabled=false"
      // ADR-010: 默认 true，走异步路径写 trigger_outbox_event，不调 OrchestratorAdapter HTTP
    })
@Import(QuartzLaunchJobIntegrationTest.TestConfig.class)
@DisplayName("QuartzLaunchJob 集成:Quartz 触发携带的作业参数走异步路径写出箱事件,不直连编排服务发起 HTTP 调用")
class QuartzLaunchJobIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  QuartzLaunchJob quartzLaunchJob;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanUp() {
    final String deleteScheduledOccurrenceCatchUpAuditSql = """
        delete from batch.scheduled_occurrence_catch_up_audit where tenant_id like 'quartz-fire-%'
    """;
    final String deleteScheduledOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id like 'quartz-fire-%'
    """;
    final String deleteScheduledOccurrenceCoverageSql = """
        delete from batch.scheduled_occurrence_coverage where tenant_id like 'quartz-fire-%'
    """;
    final String deleteTriggerMisfirePendingSql = """
        delete from batch.trigger_misfire_pending where tenant_id like 'quartz-fire-%'
    """;
    final String deleteTriggerOutboxEventSql = """
        delete from batch.trigger_outbox_event where tenant_id like 'quartz-fire-%'
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id like 'quartz-fire-%'
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id like 'quartz-fire-%'
    """;
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id like 'quartz-fire-%'
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id like 'quartz-fire-%'
    """;
    final String deleteTenantSql = """
        delete from batch.tenant where tenant_id like 'quartz-fire-%'
    """;

    jdbcTemplate.update(deleteScheduledOccurrenceCatchUpAuditSql);
    jdbcTemplate.update(deleteScheduledOccurrenceSql);
    jdbcTemplate.update(deleteScheduledOccurrenceCoverageSql);
    jdbcTemplate.update(deleteTriggerMisfirePendingSql);
    jdbcTemplate.update(deleteTriggerOutboxEventSql);
    jdbcTemplate.update(deleteTriggerRequestSql);
    jdbcTemplate.update(deleteWorkflowDefinitionSql);
    jdbcTemplate.update(deleteBatchDayCatchupControlSql);
    jdbcTemplate.update(deleteBusinessCalendarSql);
    jdbcTemplate.update(deleteTenantSql);
  }

  @Test
  @DisplayName("Quartz 按 jobDataMap 触发一次调度,应恰好落库一条 trigger_outbox_event 启动事件")
  void shouldWriteOutboxEventWhenQuartzFires() throws Exception {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertBusinessCalendarSql = """
          insert into batch.business_calendar (
              tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
              catch_up_policy, catch_up_max_days, cutoff_time, late_arrival_tolerance_min,
              sla_offset_min, enabled
          ) values (?, 'test_cal', 'Test calendar', 'UTC', 'SKIP', 'AUTO', 3,
                    ?, 30, 120, true)
          """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
              schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled,
              calendar_code
          ) values (?, ?, ?, 'DAG', 1, true, 'CRON', '0/1 * * * * ?', 'UTC',
                    'SCHEDULED', true, 'test_cal') returning id
          """;
    final String selectCountSql = """
        select count(*) from batch.trigger_outbox_event where tenant_id = ?
    """;

    JobExecutionContext context = mock(JobExecutionContext.class);
    String tenantId = "quartz-fire-" + UUID.randomUUID();
    String workflowCode = "IMPORT_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, LocalTime.MIDNIGHT);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, workflowCode);
    JobDataMap jobDataMap = new JobDataMap();
    jobDataMap.put(QuartzLaunchJob.TENANT_ID, tenantId);
    jobDataMap.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, workflowDefinitionId);
    jobDataMap.put(QuartzLaunchJob.WORKFLOW_CODE, workflowCode);
    jobDataMap.put(QuartzLaunchJob.WORKFLOW_VERSION, 1);
    jobDataMap.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
    jobDataMap.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, "0/1 * * * * ?");
    jobDataMap.put(QuartzLaunchJob.TIMEZONE, "UTC");
    jobDataMap.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
    jobDataMap.put(QuartzLaunchJob.CALENDAR_CODE, "test_cal");
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_POLICY, "NONE");
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 0);
    when(context.getMergedJobDataMap()).thenReturn(jobDataMap);
    when(context.getScheduledFireTime())
        .thenReturn(Date.from(Instant.parse("2026-03-27T00:00:00Z")));
    when(context.getFireTime()).thenReturn(Date.from(Instant.parse("2026-03-27T00:00:00Z")));

    quartzLaunchJob.execute(context);

    // ADR-010: 异步路径写 trigger_outbox_event 而非调 HTTP adapter
    Integer outboxCount = jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId);
    assertThat(outboxCount).as("Quartz fire should produce one outbox event").isEqualTo(1);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    Scheduler scheduler() {
      return mock(Scheduler.class);
    }
  }
}
