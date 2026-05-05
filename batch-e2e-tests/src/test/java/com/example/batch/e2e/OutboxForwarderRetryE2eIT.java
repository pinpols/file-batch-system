package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.RetryScheduleStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.e2e.apps.E2eImportApplication;
import com.example.batch.e2e.support.E2eStatusLogger;
import com.example.batch.orchestrator.application.engine.OutboxPublisher;
import com.example.batch.orchestrator.domain.entity.EventOutboxRetryEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.EventOutboxRetryQuery;
import com.example.batch.orchestrator.mapper.EventOutboxRetryMapper;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 端到端测试：Outbox forwarder 的重试/耗尽语义（不依赖真实 Kafka）。
 *
 * <p>测试意图：只验证 forwarder 的“状态机 + 审计落库”行为，不验证 Kafka 网络与 broker。 因此这里用 {@link
 * org.springframework.test.context.bean.override.mockito.MockitoBean} 把 {@link OutboxPublisher} 替换为
 * mock，精确控制 publish 成功/失败序列。
 *
 * <p>覆盖场景：
 *
 * <ul>
 *   <li><b>场景 A：重试耗尽</b>：publisher 永远返回 false； 当 {@code batch.outbox.max-retry-attempts=2} 时，最终
 *       outbox_event 进入 {@code GIVE_UP}， 并在 {@code event_outbox_retry} 写入 {@code EXHAUSTED} 审计记录。
 *   <li><b>场景 B：短暂失败后恢复</b>：第一次 false、第二次 true； 最终 outbox_event 进入 {@code PUBLISHED}，同时审计表至少有一条
 *       {@code FAILED} 记录作为失败轨迹。
 * </ul>
 */
@SpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.outbox.poll-interval-millis=200",
      "batch.outbox.min-poll-interval-millis=200",
      "batch.outbox.retry-delay-seconds=0",
      "batch.outbox.max-retry-attempts=2",
      "batch.outbox.circuit-breaker-enabled=false"
    })
@ActiveProfiles({"test", "e2e"})
@Tag("e2e")
class OutboxForwarderRetryE2eIT extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void registerOrchestratorUrl(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @MockitoBean private OutboxPublisher outboxPublisher;

  @Autowired private OutboxEventMapper outboxEventMapper;

  @Autowired private EventOutboxRetryMapper eventOutboxRetryMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  // ── Scenario A ────────────────────────────────────────────────────────────

  @Test
  void retryExhaustion_marksGiveUp_andWritesExhaustedAuditRecord() {
    // publisher always fails
    when(outboxPublisher.publish(any())).thenReturn(CompletableFuture.completedFuture(false));

    OutboxEventEntity event = seedOutboxEvent("t1", "e2e-exhausted-001");

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              E2eStatusLogger.logOutboxSnapshot(
                  jdbcTemplate, "t1", eventKey(event), "OutboxForwarderRetryE2eIT");
              String status =
                  jdbcTemplate.queryForObject(
                      "select publish_status from batch.outbox_event where id = ?",
                      String.class,
                      event.getId());
              assertThat(status).isEqualTo(OutboxPublishStatus.GIVE_UP.code());
            });

    // publish_attempt should equal max-retry-attempts
    Integer attempt =
        jdbcTemplate.queryForObject(
            "select publish_attempt from batch.outbox_event where id = ?",
            Integer.class,
            event.getId());
    assertThat(attempt).isEqualTo(2);

    // audit trail: at least one EXHAUSTED retry record
    List<EventOutboxRetryEntity> retries =
        eventOutboxRetryMapper.selectByQuery(
            new EventOutboxRetryQuery("t1", null, "e2e-exhausted-001"));
    assertThat(retries).isNotEmpty();
    assertThat(retries)
        .anyMatch(r -> RetryScheduleStatus.EXHAUSTED.code().equals(r.getRetryStatus()));
  }

  // ── Scenario B ────────────────────────────────────────────────────────────

  @Test
  void transientFailure_thenRecovery_eventIsPublishedEventually() {
    // fail on first publish attempt, succeed on the second
    when(outboxPublisher.publish(any()))
        .thenReturn(CompletableFuture.completedFuture(false))
        .thenReturn(CompletableFuture.completedFuture(true));

    OutboxEventEntity event = seedOutboxEvent("t1", "e2e-recovery-001");

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              E2eStatusLogger.logOutboxSnapshot(
                  jdbcTemplate, "t1", eventKey(event), "OutboxForwarderRetryE2eIT");
              String status =
                  jdbcTemplate.queryForObject(
                      "select publish_status from batch.outbox_event where id = ?",
                      String.class,
                      event.getId());
              assertThat(status).isEqualTo(OutboxPublishStatus.PUBLISHED.code());
            });

    // audit trail: at least one FAILED retry record for the failed attempt
    List<EventOutboxRetryEntity> retries =
        eventOutboxRetryMapper.selectByQuery(
            new EventOutboxRetryQuery("t1", null, "e2e-recovery-001"));
    assertThat(retries).isNotEmpty();
    assertThat(retries).anyMatch(r -> RetryScheduleStatus.FAILED.code().equals(r.getRetryStatus()));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private OutboxEventEntity seedOutboxEvent(String tenantId, String eventKey) {
    OutboxEventEntity entity = new OutboxEventEntity();
    entity.setTenantId(tenantId);
    entity.setAggregateType("E2E_TEST");
    entity.setAggregateId(BatchDateTimeSupport.utcEpochMillis());
    entity.setEventType("E2E_TEST_EVENT");
    entity.setEventKey(eventKey);
    entity.setPayloadJson("{\"test\":true}");
    entity.setPublishStatus(OutboxPublishStatus.NEW.code());
    entity.setPublishAttempt(0);
    entity.setTraceId("e2e-tr-" + eventKey);
    outboxEventMapper.insert(entity);
    return entity;
  }

  private String eventKey(OutboxEventEntity event) {
    return event.getEventKey();
  }
}
