package io.github.pinpols.batch.console.integration;

import io.github.pinpols.batch.console.domain.notification.param.AlertEscalationNotificationOutboxMarkFailedParam;
import io.github.pinpols.batch.console.domain.notification.param.AlertEscalationNotificationOutboxMarkGiveUpParam;
import io.github.pinpols.batch.console.domain.notification.param.AlertEscalationNotificationOutboxMarkPublishingParam;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.notification.entity.AlertEscalationNotificationOutboxEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEscalationNotificationOutboxMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.service.AlertEscalationNotificationOutboxService;
import io.github.pinpols.batch.console.domain.notification.service.AlertEscalationNotifier;
import io.github.pinpols.batch.console.domain.notification.service.AlertEscalationNotifier.AlertEscalationNotifyPayload;
import io.github.pinpols.batch.console.shared.event.ConsoleRealtimeDomainEvent;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 集成测试:验证升级通知的两个新 SQL 对真实 PG 的语义正确(V181 列 + 谓词 + CAS 水位线)。
 *
 * <p>单元测试 {@code AlertEscalationNotifierTest} mock 掉 mapper 只验编排,SQL 本身的列名 / {@code >} 谓词 / CAS
 * 守护必须在真库上验,避免「全部通过但 SQL 错」(conformance≠production)。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "batch.alert.escalation.notify.enabled=true",
      "batch.alert.escalation.notify.poll-interval-millis=600000"
    })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("升级通知映射与水位线: 真库验证筛选谓词,CAS 推进与待发记录状态流转")
class AlertEscalationNotifyMapperIntegrationTest extends AbstractIntegrationTest {

  private static final String SELECT_ESCALATION_OUTBOX_SQL = """
      select tenant_id, alert_event_id, escalation_tier, stream, event_type,
             payload_json, publish_status
      from batch.alert_escalation_notification_outbox
      where tenant_id = ? and alert_event_id = ? and escalation_tier = ?
      """;
  private static final String DELETE_ESCALATION_OUTBOX_SQL = """
      delete from batch.alert_escalation_notification_outbox
      where tenant_id = ? and alert_event_id = ?
      """;
  private static final String DELETE_ALERT_BY_ID_SQL = """
      delete from batch.alert_event where tenant_id = ? and id = ?
      """;
  private static final String INSERT_NOTIFICATION_CHANNEL_SQL = """
      insert into batch.notification_channel
        (tenant_id, channel_code, channel_name, channel_type, config_json, enabled)
      values (?, ?, ?, 'WEBHOOK', ?::jsonb, true)
      """;
  private static final String INSERT_SUBSCRIPTION_RULE_SQL = """
      insert into batch.subscription_rule
        (tenant_id, rule_name, channel_code, event_types, severity_filter, enabled)
      values (?, ?, ?, 'ALERT_ESCALATED', 'CRITICAL', true)
      """;
  private static final String SELECT_NOTIFICATION_DELIVERY_SQL = """
      select alert_event_id, delivery_status, attempt
      from batch.notification_delivery_log
      where tenant_id = ? and event_type = 'ALERT_ESCALATED'
      """;
  private static final String DELETE_NOTIFICATION_DELIVERIES_SQL = """
      delete from batch.notification_delivery_log where tenant_id = ?
      """;
  private static final String DELETE_SUBSCRIPTION_RULES_SQL = """
      delete from batch.subscription_rule where tenant_id = ?
      """;
  private static final String DELETE_NOTIFICATION_CHANNELS_SQL = """
      delete from batch.notification_channel where tenant_id = ?
      """;

  private final AlertEventMapper alertEventMapper;

  private final AlertEscalationNotificationOutboxMapper outboxMapper;

  private final AlertEscalationNotificationOutboxService outboxService;

  private final JdbcTemplate jdbcTemplate;

  @MockitoBean
  private ConsoleRealtimeEventPort consoleRealtimeEventPort;

  private final AlertEscalationNotifier notifier;

  @Autowired
  private SubscriptionRuleMapper subscriptionRuleMapper;

  @Autowired
  private ApplicationEventPublisher applicationEventPublisher;

  AlertEscalationNotifyMapperIntegrationTest(
      AlertEventMapper alertEventMapper,
      AlertEscalationNotificationOutboxMapper outboxMapper,
      AlertEscalationNotificationOutboxService outboxService,
      JdbcTemplate jdbcTemplate,
      AlertEscalationNotifier notifier) {
    this.alertEventMapper = alertEventMapper;
    this.outboxMapper = outboxMapper;
    this.outboxService = outboxService;
    this.jdbcTemplate = jdbcTemplate;
    this.notifier = notifier;
  }

  @Test
  @DisplayName("筛选已升级未通知的未关闭告警: 只命中该条,CAS 推进水位线后不再被选中")
  void shouldSelectOnlyEscalatedPendingOpenRowsThenStopAfterWatermarkBump() {
    String tenantId = "t-esc-notify-" + BatchDateTimeSupport.utcEpochMillis();
    // 已升级未通知:应被选中
    long pending = insertAlert(tenantId, "SLA_BREACH", "OPEN", 2, 0);
    // tier=0 未升级:0 > 0 为假,不选
    long notEscalated = insertAlert(tenantId, "FILE_STUCK", "OPEN", 0, 0);
    // 已升级且已通知到位:2 > 2 为假,不选
    insertAlert(tenantId, "DISK_USAGE", "OPEN", 2, 2);
    // 升级但已 ACKED:status 谓词排除
    insertAlert(tenantId, "QUEUE_LAG", "ACKED", 3, 0);

    List<AlertEventEntity> firstScan = onlyTenant(tenantId);
    assertThat(firstScan).extracting(AlertEventEntity::getId).containsExactly(pending);
    assertThat(firstScan).extracting(AlertEventEntity::getId).doesNotContain(notEscalated);

    // CAS 推进水位线:expected=0 命中 → 1 行
    int marked = alertEventMapper.markEscalationNotified(tenantId, pending, 0, 2);
    assertThat(marked).isEqualTo(1);
    AlertEventEntity after = alertEventMapper.selectById(tenantId, pending);
    assertThat(after.getEscalationNotifiedTier()).isEqualTo(2);

    // 推进后不再被选(2 > 2 为假)
    assertThat(onlyTenant(tenantId)).isEmpty();
  }

  @Test
  @DisplayName("期望水位线与实际不符: 比较并交换不命中,水位线保持不变")
  void shouldNotBumpWatermarkWhenExpectedTierMismatches() {
    String tenantId = "t-esc-cas-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlert(tenantId, "SLA_BREACH", "OPEN", 1, 0);

    // 期望水位线=5 与实际 0 不符 → CAS 不命中,0 行
    int marked = alertEventMapper.markEscalationNotified(tenantId, alertId, 5, 1);
    assertThat(marked).isZero();
    assertThat(alertEventMapper.selectById(tenantId, alertId).getEscalationNotifiedTier())
        .isZero();
  }

  @Test
  @DisplayName("告警非未关闭状态: 水位线不推进")
  void shouldNotBumpWatermarkOnNonOpenAlert() {
    String tenantId = "t-esc-closed-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlert(tenantId, "SLA_BREACH", "CLOSED", 2, 0);

    int marked = alertEventMapper.markEscalationNotified(tenantId, alertId, 0, 2);
    assertThat(marked).isZero();
  }

  @Test
  @DisplayName("入队与水位线同事务: 待发记录落库,事项内容与层级正确,水位线同步推进")
  void shouldCreateOutboxInSameTransactionAsWatermarkBump() {
    String tenantId = "t-esc-outbox-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlert(tenantId, "SLA_BREACH", "OPEN", 2, 0);
    AlertEventEntity alert = alertEventMapper.selectById(tenantId, alertId);

    boolean enqueued = outboxService.enqueue(
        alert,
        "alerts",
        "ALERT_ESCALATED",
        new AlertEscalationNotifyPayload(
            alertId, "SLA_BREACH", "CRITICAL", "SLA_BREACH escalated", 2, "trace-" + alertId));

    assertThat(enqueued).isTrue();
    assertThat(alertEventMapper.selectById(tenantId, alertId).getEscalationNotifiedTier())
        .isEqualTo(2);
    AlertEscalationNotificationOutboxEntity row = findOutboxByTenantAndAlert(tenantId, alertId);
    assertThat(row.getPublishStatus()).isEqualTo(OutboxPublishStatus.NEW.code());
    assertThat(row.getEscalationTier()).isEqualTo(2);
    AlertEscalationNotifyPayload payload =
        JsonUtils.fromJson(row.getPayloadJson(), AlertEscalationNotifyPayload.class);
    assertThat(payload.alertId()).isEqualTo(alertId);
    assertThat(payload.escalationTier()).isEqualTo(2);
  }

  @Test
  @DisplayName("已有同键待发记录: 入队抛出重复键异常并回滚水位线")
  void shouldRollbackWatermarkWhenOutboxInsertViolatesUniqueKey() {
    String tenantId = "t-esc-outbox-rollback-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlert(tenantId, "SLA_BREACH", "OPEN", 3, 0);
    insertOutbox(
        tenantId, alertId, 3, OutboxPublishStatus.NEW.code(), 0, BatchDateTimeSupport.utcNow());
    AlertEventEntity alert = alertEventMapper.selectById(tenantId, alertId);

    assertThatThrownBy(() -> outboxService.enqueue(
            alert,
            "alerts",
            "ALERT_ESCALATED",
            new AlertEscalationNotifyPayload(
                alertId, "SLA_BREACH", "CRITICAL", "SLA_BREACH escalated", 3, "trace-" + alertId)))
        .isInstanceOf(DuplicateKeyException.class);

    assertThat(alertEventMapper.selectById(tenantId, alertId).getEscalationNotifiedTier())
        .isZero();
  }

  @Test
  @DisplayName("同租户同告警同层级重复写入: 第二次触发唯一键冲突")
  void shouldEnforceUniqueOutboxPerTenantAlertAndTier() {
    String tenantId = "t-esc-outbox-unique-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlert(tenantId, "SLA_BREACH", "OPEN", 1, 0);

    insertOutbox(
        tenantId, alertId, 1, OutboxPublishStatus.NEW.code(), 0, BatchDateTimeSupport.utcNow());

    assertThatThrownBy(() -> insertOutbox(
            tenantId, alertId, 1, OutboxPublishStatus.NEW.code(), 0, BatchDateTimeSupport.utcNow()))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @DisplayName("待发记录状态流转: 发布成功,发布失败与放弃重试三种终态各归其位")
  void shouldMoveOutboxThroughPublishStatusLifecycle() {
    String tenantId = "t-esc-outbox-flow-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlert(tenantId, "SLA_BREACH", "OPEN", 1, 0);
    Instant now = BatchDateTimeSupport.utcNow();
    long publishedId =
        insertOutbox(tenantId, alertId, 1, OutboxPublishStatus.NEW.code(), 0, now.minusSeconds(1));

    List<AlertEscalationNotificationOutboxEntity> pending = outboxMapper.selectPending(
        now, 500, OutboxPublishStatus.NEW.code(), OutboxPublishStatus.FAILED.code());
    assertThat(pending)
        .extracting(AlertEscalationNotificationOutboxEntity::getId)
        .contains(publishedId);

    assertThat(outboxMapper.markPublishing(
            AlertEscalationNotificationOutboxMarkPublishingParam.builder()
                .id(publishedId)
                .tenantId(tenantId)
                .publishingStatus(OutboxPublishStatus.PUBLISHING.code())
                .pendingStatus1(OutboxPublishStatus.NEW.code())
                .pendingStatus2(OutboxPublishStatus.FAILED.code())
                .build()))
        .isEqualTo(1);
    assertThat(outboxMapper.markPublished(
            publishedId,
            tenantId,
            OutboxPublishStatus.PUBLISHED.code(),
            OutboxPublishStatus.PUBLISHING.code()))
        .isEqualTo(1);
    assertThat(outboxMapper.markPublished(
            publishedId,
            tenantId,
            OutboxPublishStatus.PUBLISHED.code(),
            OutboxPublishStatus.PUBLISHING.code()))
        .isZero();

    long failedId =
        insertOutbox(tenantId, alertId, 2, OutboxPublishStatus.NEW.code(), 0, now.minusSeconds(1));
    assertThat(outboxMapper.markPublishing(
            AlertEscalationNotificationOutboxMarkPublishingParam.builder()
                .id(failedId)
                .tenantId(tenantId)
                .publishingStatus(OutboxPublishStatus.PUBLISHING.code())
                .pendingStatus1(OutboxPublishStatus.NEW.code())
                .pendingStatus2(OutboxPublishStatus.FAILED.code())
                .build()))
        .isEqualTo(1);
    assertThat(outboxMapper.markFailed(
            AlertEscalationNotificationOutboxMarkFailedParam.builder()
                .id(failedId)
                .tenantId(tenantId)
                .failedStatus(OutboxPublishStatus.FAILED.code())
                .nextPublishAt(now.plusSeconds(60))
                .lastError("publish failed")
                .publishingStatus(OutboxPublishStatus.PUBLISHING.code())
                .build()))
        .isEqualTo(1);

    long giveUpId =
        insertOutbox(tenantId, alertId, 3, OutboxPublishStatus.NEW.code(), 9, now.minusSeconds(1));
    assertThat(outboxMapper.markPublishing(
            AlertEscalationNotificationOutboxMarkPublishingParam.builder()
                .id(giveUpId)
                .tenantId(tenantId)
                .publishingStatus(OutboxPublishStatus.PUBLISHING.code())
                .pendingStatus1(OutboxPublishStatus.NEW.code())
                .pendingStatus2(OutboxPublishStatus.FAILED.code())
                .build()))
        .isEqualTo(1);
    assertThat(outboxMapper.markGiveUp(
            AlertEscalationNotificationOutboxMarkGiveUpParam.builder()
                .id(giveUpId)
                .tenantId(tenantId)
                .giveUpStatus(OutboxPublishStatus.GIVE_UP.code())
                .lastError("retry exhausted")
                .publishingStatus(OutboxPublishStatus.PUBLISHING.code())
                .build()))
        .isEqualTo(1);

    assertThat(statusOf(publishedId)).isEqualTo(OutboxPublishStatus.PUBLISHED.code());
    assertThat(statusOf(failedId)).isEqualTo(OutboxPublishStatus.FAILED.code());
    assertThat(statusOf(giveUpId)).isEqualTo(OutboxPublishStatus.GIVE_UP.code());
  }

  @Test
  @DisplayName("生产 notifier 将升级告警写入 outbox 并发布实时领域事件")
  void shouldPublishEscalatedAlertFromDatabaseThroughNotifier() {
    String tenantId = "t-esc-notifier-e2e-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlert(tenantId, "SLA_BREACH", "OPEN", 2, 0);

    try {
      awaitEscalationNotificationWatermark(tenantId, alertId, 2);

      assertThat(alertEventMapper.selectById(tenantId, alertId).getEscalationNotifiedTier())
          .isEqualTo(2);
      List<AlertEscalationNotificationOutboxEntity> rows = jdbcTemplate.query(
          SELECT_ESCALATION_OUTBOX_SQL,
          (resultSet, rowNumber) -> {
            AlertEscalationNotificationOutboxEntity row =
                new AlertEscalationNotificationOutboxEntity();
            row.setTenantId(resultSet.getString("tenant_id"));
            row.setAlertEventId(resultSet.getLong("alert_event_id"));
            row.setEscalationTier(resultSet.getInt("escalation_tier"));
            row.setStream(resultSet.getString("stream"));
            row.setEventType(resultSet.getString("event_type"));
            row.setPayloadJson(resultSet.getString("payload_json"));
            row.setPublishStatus(resultSet.getString("publish_status"));
            return row;
          },
          tenantId,
          alertId,
          2);
      assertThat(rows).hasSize(1);
      AlertEscalationNotificationOutboxEntity row = rows.get(0);
      assertThat(row.getPublishStatus()).isEqualTo(OutboxPublishStatus.PUBLISHED.code());
      assertThat(row.getStream()).isEqualTo("alerts");
      assertThat(row.getEventType()).isEqualTo("ALERT_ESCALATED");
      AlertEscalationNotifyPayload payload =
          JsonUtils.fromJson(row.getPayloadJson(), AlertEscalationNotifyPayload.class);
      assertThat(payload.alertId()).isEqualTo(alertId);
      assertThat(payload.escalationTier()).isEqualTo(2);

      ArgumentCaptor<Object> publishedPayload = ArgumentCaptor.forClass(Object.class);
      verify(consoleRealtimeEventPort)
          .publishChanged(
              eq(tenantId), eq("alerts"), eq("ALERT_ESCALATED"), publishedPayload.capture());
      assertThat(publishedPayload.getValue()).isEqualTo(payload);
    } finally {
      jdbcTemplate.update(DELETE_ESCALATION_OUTBOX_SQL, tenantId, alertId);
      jdbcTemplate.update(DELETE_ALERT_BY_ID_SQL, tenantId, alertId);
    }
  }

  @Test
  @DisplayName("告警升级事件经通知规则 severity 过滤后实际投递 webhook")
  void shouldDeliverEscalationEventThroughConfiguredWebhookRule() throws Exception {
    String tenantId = "t-esc-webhook-e2e-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlert(tenantId, "SLA_BREACH", "OPEN", 2, 0);
    ConcurrentLinkedQueue<String> requestBodies = new ConcurrentLinkedQueue<>();
    HttpServer webhookServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    webhookServer.createContext("/alerts", exchange -> {
      requestBodies.add(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    webhookServer.start();
    String previousAllowPrivate = System.getProperty("batch.security.ssrf-guard.allow-private");
    String channelCode = "escalation-webhook-" + alertId;
    String ruleName = "escalation-rule-" + alertId;
    String targetUrl = "http://127.0.0.1:" + webhookServer.getAddress().getPort() + "/alerts";

    try {
      System.setProperty("batch.security.ssrf-guard.allow-private", "true");
      doAnswer(invocation -> {
            String eventTenantId = invocation.getArgument(0);
            String stream = invocation.getArgument(1);
            String eventType = invocation.getArgument(2);
            Object data = invocation.getArgument(3);
            applicationEventPublisher.publishEvent(
                ConsoleRealtimeDomainEvent.builder().tenantId(eventTenantId).stream(stream)
                    .eventType(eventType)
                    .cursor("alert-escalation-" + alertId)
                    .data(data)
                    .emittedAt(BatchDateTimeSupport.utcNow())
                    .build());
            return null;
          })
          .when(consoleRealtimeEventPort)
          .publishChanged(eq(tenantId), eq("alerts"), eq("ALERT_ESCALATED"), any());
      jdbcTemplate.update(
          INSERT_NOTIFICATION_CHANNEL_SQL,
          tenantId,
          channelCode,
          channelCode,
          JsonUtils.toJson(java.util.Map.of("url", targetUrl)));
      jdbcTemplate.update(INSERT_SUBSCRIPTION_RULE_SQL, tenantId, ruleName, channelCode);
      awaitEscalationNotificationWatermark(tenantId, alertId, 2);
      List<AlertEscalationNotificationOutboxEntity> notificationOutbox = jdbcTemplate.query(
          SELECT_ESCALATION_OUTBOX_SQL,
          (resultSet, rowNumber) -> {
            AlertEscalationNotificationOutboxEntity row =
                new AlertEscalationNotificationOutboxEntity();
            row.setPayloadJson(resultSet.getString("payload_json"));
            return row;
          },
          tenantId,
          alertId,
          2);
      assertThat(notificationOutbox).hasSize(1);
      AlertEscalationNotifyPayload notificationPayload = JsonUtils.fromJson(
          notificationOutbox.getFirst().getPayloadJson(), AlertEscalationNotifyPayload.class);
      verify(consoleRealtimeEventPort)
          .publishChanged(
              eq(tenantId), eq("alerts"), eq("ALERT_ESCALATED"), eq(notificationPayload));
      assertThat(subscriptionRuleMapper.selectEnabledByEventType(tenantId, "ALERT_ESCALATED"))
          .hasSize(1);
      assertThat(JsonUtils.toMap(notificationPayload)).containsEntry("severity", "CRITICAL");

      await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
        assertThat(requestBodies).hasSize(1);
        List<DeliveryRow> deliveries = jdbcTemplate.query(
            SELECT_NOTIFICATION_DELIVERY_SQL,
            (resultSet, rowNumber) -> new DeliveryRow(
                resultSet.getLong("alert_event_id"),
                resultSet.getString("delivery_status"),
                resultSet.getInt("attempt")),
            tenantId);
        assertThat(deliveries).containsExactly(new DeliveryRow(alertId, "SUCCESS", 1));
      });
      JsonNode webhookPayload = new ObjectMapper().readTree(requestBodies.peek());
      assertThat(webhookPayload.path("eventType").asText()).isEqualTo("ALERT_ESCALATED");
      assertThat(webhookPayload.path("data").path("alertId").asLong()).isEqualTo(alertId);
      assertThat(webhookPayload.path("data").path("severity").asText()).isEqualTo("CRITICAL");
    } finally {
      reset(consoleRealtimeEventPort);
      jdbcTemplate.update(DELETE_NOTIFICATION_DELIVERIES_SQL, tenantId);
      jdbcTemplate.update(DELETE_SUBSCRIPTION_RULES_SQL, tenantId);
      jdbcTemplate.update(DELETE_NOTIFICATION_CHANNELS_SQL, tenantId);
      jdbcTemplate.update(DELETE_ESCALATION_OUTBOX_SQL, tenantId, alertId);
      jdbcTemplate.update(DELETE_ALERT_BY_ID_SQL, tenantId, alertId);
      webhookServer.stop(0);
      if (previousAllowPrivate == null) {
        System.clearProperty("batch.security.ssrf-guard.allow-private");
      } else {
        System.setProperty("batch.security.ssrf-guard.allow-private", previousAllowPrivate);
      }
    }
  }

  private void awaitEscalationNotificationWatermark(
      String tenantId, long alertId, int expectedTier) {
    // 同类场景共享真实 ShedLock；以本告警已提交水位线确认获锁执行，而不是假设一次 poll 必然运行。
    await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
      notifier.poll();
      AlertEventEntity notifiedAlert = alertEventMapper.selectById(tenantId, alertId);
      assertThat(notifiedAlert.getEscalationNotifiedTier()).isEqualTo(expectedTier);
    });
  }

  private record DeliveryRow(long alertEventId, String status, int attempt) {}

  private List<AlertEventEntity> onlyTenant(String tenantId) {
    return alertEventMapper.selectEscalatedPendingNotify(100).stream()
        .filter(a -> tenantId.equals(a.getTenantId()))
        .toList();
  }

  private long insertAlert(
      String tenantId, String alertType, String status, int tier, int notifiedTier) {
    final String insertAlertEventSql = """
    INSERT INTO batch.alert_event
    (tenant_id, service_name, alert_type, severity, title, detail_json, dedup_fingerprint,
    occurrence_count, first_seen_at, last_seen_at, status, escalation_tier, escalated_at,
    escalation_notified_tier, created_at, updated_at)
    VALUES (?, 'batch-orchestrator', ?, 'CRITICAL', ?, '{}', ?,
    1, ?, ?, ?, ?, ?, ?, now(), now())
    """;
    String title = alertType + " escalated";
    String dedupFingerprint = tenantId + ":" + alertType + ":" + System.nanoTime();
    Timestamp firstSeenAt = Timestamp.from(BatchDateTimeSupport.utcNow());
    Timestamp lastSeenAt = Timestamp.from(BatchDateTimeSupport.utcNow());
    Timestamp escalatedAt = Timestamp.from(BatchDateTimeSupport.utcNow());
    jdbcTemplate.update(
        insertAlertEventSql,
        tenantId,
        alertType,
        title,
        dedupFingerprint,
        firstSeenAt,
        lastSeenAt,
        status,
        tier,
        escalatedAt,
        notifiedTier);
    final String latestAlertEventIdSql = """
    select id from batch.alert_event where tenant_id = ? order by id desc limit 1
    """;
    Long id = jdbcTemplate.queryForObject(latestAlertEventIdSql, Long.class, tenantId);
    return id == null ? -1L : id;
  }

  private long insertOutbox(
      String tenantId,
      long alertId,
      int escalationTier,
      String publishStatus,
      int attemptCount,
      Instant nextPublishAt) {
    AlertEscalationNotificationOutboxEntity row = new AlertEscalationNotificationOutboxEntity();
    row.setTenantId(tenantId);
    row.setAlertEventId(alertId);
    row.setEscalationTier(escalationTier);
    row.setStream("alerts");
    row.setEventType("ALERT_ESCALATED");
    row.setPayloadJson("""
        {"alertId":%d,"alertType":"SLA_BREACH","severity":"CRITICAL","title":"SLA_BREACH escalated","escalationTier":%d}
        """.formatted(alertId, escalationTier));
    row.setPublishStatus(publishStatus);
    row.setAttemptCount(attemptCount);
    row.setNextPublishAt(nextPublishAt);
    int inserted = outboxMapper.insert(row);
    assertThat(inserted).isEqualTo(1);
    final String alertEscalationOutboxIdSql = """
    SELECT id
    FROM batch.alert_escalation_notification_outbox
    WHERE tenant_id = ?
    AND alert_event_id = ?
    AND escalation_tier = ?
    """;
    Long id = jdbcTemplate.queryForObject(
        alertEscalationOutboxIdSql, Long.class, tenantId, alertId, escalationTier);
    return id == null ? -1L : id;
  }

  private AlertEscalationNotificationOutboxEntity findOutboxByTenantAndAlert(
      String tenantId, long alertId) {
    List<AlertEscalationNotificationOutboxEntity> rows = outboxMapper.selectPending(
        BatchDateTimeSupport.utcNow().plusSeconds(1),
        500,
        OutboxPublishStatus.NEW.code(),
        OutboxPublishStatus.FAILED.code());
    return rows.stream()
        .filter(row -> tenantId.equals(row.getTenantId()) && alertId == row.getAlertEventId())
        .findFirst()
        .orElseThrow();
  }

  private String statusOf(long id) {
    String alertEscalationOutboxStatusSql = """
        select publish_status from batch.alert_escalation_notification_outbox where id = ?
        """;
    return jdbcTemplate.queryForObject(alertEscalationOutboxStatusSql, String.class, id);
  }
}
