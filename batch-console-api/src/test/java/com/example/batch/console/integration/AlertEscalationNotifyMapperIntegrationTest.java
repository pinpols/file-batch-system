package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.notification.mapper.AlertEventMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试:验证升级通知的两个新 SQL 对真实 PG 的语义正确(V181 列 + 谓词 + CAS 水位线)。
 *
 * <p>单元测试 {@code AlertEscalationNotifierTest} mock 掉 mapper 只验编排,SQL 本身的列名 / {@code >} 谓词 / CAS
 * 守护必须在真库上验,避免「全部通过但 SQL 错」(conformance≠production)。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AlertEscalationNotifyMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired private AlertEventMapper alertEventMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

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
    assertThat(alertEventMapper.selectById(tenantId, alertId).getEscalationNotifiedTier()).isZero();
  }

  @Test
  void shouldNotBumpWatermarkOnNonOpenAlert() {
    String tenantId = "t-esc-closed-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlert(tenantId, "SLA_BREACH", "CLOSED", 2, 0);

    int marked = alertEventMapper.markEscalationNotified(tenantId, alertId, 0, 2);
    assertThat(marked).isZero();
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
    Long id =
        jdbcTemplate.queryForObject(
            "select id from batch.alert_event where tenant_id = ? order by id desc limit 1",
            Long.class,
            tenantId);
    return id == null ? -1L : id;
  }
}
