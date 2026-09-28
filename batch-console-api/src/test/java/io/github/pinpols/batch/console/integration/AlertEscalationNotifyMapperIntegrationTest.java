package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.notification.entity.AlertEscalationNotificationOutboxEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEscalationNotificationOutboxMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.notification.service.AlertEscalationNotificationOutboxService;
import io.github.pinpols.batch.console.domain.notification.service.AlertEscalationNotifier.AlertEscalationNotifyPayload;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

/**
 * 集成测试:验证升级通知的两个新 SQL 对真实 PG 的语义正确(V181 列 + 谓词 + CAS 水位线)。
 *
 * <p>单元测试 {@code AlertEscalationNotifierTest} mock 掉 mapper 只验编排,SQL 本身的列名 / {@code >} 谓词 / CAS
 * 守护必须在真库上验,避免「全部通过但 SQL 错」(conformance≠production)。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AlertEscalationNotifyMapperIntegrationTest extends AbstractIntegrationTest {

  private final AlertEventMapper alertEventMapper;

  private final AlertEscalationNotificationOutboxMapper outboxMapper;

  private final AlertEscalationNotificationOutboxService outboxService;

  private final JdbcTemplate jdbcTemplate;

  AlertEscalationNotifyMapperIntegrationTest(
      AlertEventMapper alertEventMapper,
      AlertEscalationNotificationOutboxMapper outboxMapper,
      AlertEscalationNotificationOutboxService outboxService,
      JdbcTemplate jdbcTemplate) {
    this.alertEventMapper = alertEventMapper;
    this.outboxMapper = outboxMapper;
    this.outboxService = outboxService;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
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
  void shouldNotBumpWatermarkOnNonOpenAlert() {
    String tenantId = "t-esc-closed-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlert(tenantId, "SLA_BREACH", "CLOSED", 2, 0);

    int marked = alertEventMapper.markEscalationNotified(tenantId, alertId, 0, 2);
    assertThat(marked).isZero();
  }

  @Test
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
            publishedId,
            tenantId,
            OutboxPublishStatus.PUBLISHING.code(),
            OutboxPublishStatus.NEW.code(),
            OutboxPublishStatus.FAILED.code()))
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
            failedId,
            tenantId,
            OutboxPublishStatus.PUBLISHING.code(),
            OutboxPublishStatus.NEW.code(),
            OutboxPublishStatus.FAILED.code()))
        .isEqualTo(1);
    assertThat(outboxMapper.markFailed(
            failedId,
            tenantId,
            OutboxPublishStatus.FAILED.code(),
            now.plusSeconds(60),
            "publish failed",
            OutboxPublishStatus.PUBLISHING.code()))
        .isEqualTo(1);

    long giveUpId =
        insertOutbox(tenantId, alertId, 3, OutboxPublishStatus.NEW.code(), 9, now.minusSeconds(1));
    assertThat(outboxMapper.markPublishing(
            giveUpId,
            tenantId,
            OutboxPublishStatus.PUBLISHING.code(),
            OutboxPublishStatus.NEW.code(),
            OutboxPublishStatus.FAILED.code()))
        .isEqualTo(1);
    assertThat(outboxMapper.markGiveUp(
            giveUpId,
            tenantId,
            OutboxPublishStatus.GIVE_UP.code(),
            "retry exhausted",
            OutboxPublishStatus.PUBLISHING.code()))
        .isEqualTo(1);

    assertThat(statusOf(publishedId)).isEqualTo(OutboxPublishStatus.PUBLISHED.code());
    assertThat(statusOf(failedId)).isEqualTo(OutboxPublishStatus.FAILED.code());
    assertThat(statusOf(giveUpId)).isEqualTo(OutboxPublishStatus.GIVE_UP.code());
  }

  private List<AlertEventEntity> onlyTenant(String tenantId) {
    return alertEventMapper.selectEscalatedPendingNotify(100).stream()
        .filter(a -> tenantId.equals(a.getTenantId()))
        .toList();
  }

  private long insertAlert(
      String tenantId, String alertType, String status, int tier, int notifiedTier) {
    jdbcTemplate.update(
        """
        INSERT INTO batch.alert_event
          (tenant_id, service_name, alert_type, severity, title, detail_json, dedup_fingerprint,
           occurrence_count, first_seen_at, last_seen_at, status, escalation_tier, escalated_at,
           escalation_notified_tier, created_at, updated_at)
        VALUES (?, 'batch-orchestrator', ?, 'CRITICAL', ?, '{}', ?,
                1, ?, ?, ?, ?, ?, ?, now(), now())
        """,
        tenantId,
        alertType,
        alertType + " escalated",
        tenantId + ":" + alertType + ":" + System.nanoTime(),
        Timestamp.from(BatchDateTimeSupport.utcNow()),
        Timestamp.from(BatchDateTimeSupport.utcNow()),
        status,
        tier,
        Timestamp.from(BatchDateTimeSupport.utcNow()),
        notifiedTier);
    Long id = jdbcTemplate.queryForObject(
        "select id from batch.alert_event where tenant_id = ? order by id desc limit 1",
        Long.class,
        tenantId);
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
    Long id = jdbcTemplate.queryForObject("""
        SELECT id
          FROM batch.alert_escalation_notification_outbox
         WHERE tenant_id = ?
           AND alert_event_id = ?
           AND escalation_tier = ?
        """, Long.class, tenantId, alertId, escalationTier);
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
    return jdbcTemplate.queryForObject(
        "select publish_status from batch.alert_escalation_notification_outbox where id = ?",
        String.class,
        id);
  }
}
