package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.infrastructure.QuartzLaunchJob;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
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
class QuartzLaunchJobIntegrationTest extends AbstractIntegrationTest {

  @Autowired QuartzLaunchJob quartzLaunchJob;
  @Autowired JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanUp() {
    jdbcTemplate.update("delete from batch.trigger_outbox_event");
    jdbcTemplate.update("delete from batch.trigger_request");
  }

  @Test
  void shouldWriteOutboxEventWhenQuartzFires() throws Exception {
    JobExecutionContext context = mock(JobExecutionContext.class);
    JobDataMap jobDataMap = new JobDataMap();
    jobDataMap.put(QuartzLaunchJob.TENANT_ID, "t1");
    jobDataMap.put(QuartzLaunchJob.JOB_CODE, "IMPORT_JOB");
    jobDataMap.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
    jobDataMap.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, "0/1 * * * * ?");
    jobDataMap.put(QuartzLaunchJob.TIMEZONE, "UTC");
    jobDataMap.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
    jobDataMap.put(QuartzLaunchJob.CALENDAR_CODE, "");
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_POLICY, "NONE");
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 0);
    when(context.getMergedJobDataMap()).thenReturn(jobDataMap);
    when(context.getScheduledFireTime())
        .thenReturn(Date.from(Instant.parse("2026-03-27T00:00:00Z")));
    when(context.getFireTime()).thenReturn(Date.from(Instant.parse("2026-03-27T00:00:00Z")));

    quartzLaunchJob.execute(context);

    // ADR-010: 异步路径写 trigger_outbox_event 而非调 HTTP adapter
    Integer outboxCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_outbox_event where tenant_id = ?",
            Integer.class,
            "t1");
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
