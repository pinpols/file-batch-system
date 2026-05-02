package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.batch.common.enums.TriggerType;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.service.TriggerService;
import com.example.batch.trigger.web.request.TriggerLaunchRequest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * REQUIRES_NEW 事务边界集成测试 — Trigger 去重（ADR-010 异步路径）。
 *
 * <p>{@code DefaultTriggerService#insertPendingAndOutboxOrReturnExisting} 使用 {@code
 * PROPAGATION_REQUIRES_NEW} 在同一事务内原子写 trigger_request + trigger_outbox_event。验证：去重检查生效、两表原子落库。
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration",
      "batch.orchestrator.base-url=http://localhost:8082",
      // 关 QuartzMetricsConfiguration：本测试用 mock(Scheduler) 而非真 Quartz，
      // 否则 mock 的 getListenerManager() 返回 null 在 @PostConstruct 触发 NPE
      "batch.trigger.quartz-metrics.enabled=false",
      // ADR-010: 默认 true，走异步路径写 trigger_outbox_event，不调 orchestrator HTTP
    })
@Import(TriggerDedupRequiresNewIntegrationTest.TestConfig.class)
class TriggerDedupRequiresNewIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TriggerService triggerService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanUp() {
    jdbcTemplate.update("delete from batch.trigger_outbox_event");
    jdbcTemplate.update("delete from batch.trigger_request");
  }

  @Test
  void asyncLaunchWritesBothRequestAndOutboxInSameTransaction() {
    TriggerLaunchCommand command = buildCommand("ASYNC_WRITE", "idem-async", "req-async");

    var response = triggerService.launch(command);
    assertThat(response).isNotNull();

    // trigger_request 已落库且状态为 ACCEPTED
    Integer requestCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ?",
            Integer.class,
            "t1",
            "idem-async");
    assertThat(requestCount).as("trigger_request should be persisted").isEqualTo(1);

    String status =
        jdbcTemplate.queryForObject(
            "select request_status from batch.trigger_request where tenant_id = ? and dedup_key ="
                + " ?",
            String.class,
            "t1",
            "idem-async");
    assertThat(status).as("async path should set ACCEPTED status").isEqualTo("ACCEPTED");

    // trigger_outbox_event 与 trigger_request 在同一 REQUIRES_NEW 事务内原子落库
    Integer outboxCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id ="
                + " ?",
            Integer.class,
            "t1",
            "req-async");
    assertThat(outboxCount)
        .as("trigger_outbox_event should be written atomically with trigger_request")
        .isEqualTo(1);
  }

  @Test
  void dedupCheckPreventsSecondInsertInNewTransaction() {
    TriggerLaunchCommand first = buildCommand("DEDUP_TWICE", "idem-twice", "req-first");
    TriggerLaunchCommand second = buildCommand("DEDUP_TWICE", "idem-twice", "req-second");

    triggerService.launch(first);
    triggerService.launch(second);

    // 两次调用相同 dedupKey，只产生一条记录
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ?",
            Integer.class,
            "t1",
            "idem-twice");
    assertThat(count).isEqualTo(1);
  }

  private TriggerLaunchCommand buildCommand(String jobCode, String dedupKey, String requestId) {
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setJobCode(jobCode);
    request.setBizDate(LocalDate.of(2026, 3, 27));
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of());
    return new TriggerLaunchCommand(request, dedupKey, requestId, "trace-dedup");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    Scheduler scheduler() {
      return mock(Scheduler.class);
    }
  }
}
