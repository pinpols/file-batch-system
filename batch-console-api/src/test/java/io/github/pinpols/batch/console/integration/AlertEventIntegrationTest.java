package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.notification.query.AlertEventQuery;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 集成测试：alert_event 表通过 AlertEventMapper 的持久化与查询验证。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("告警事件映射: 按等级,状态,类型与最近出现时间范围查询及条数上限")
class AlertEventIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private AlertEventMapper alertEventMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("无数据租户: 查询返回空列表")
  void shouldReturnEmptyWhenNoAlertsExist() {
    List<AlertEventEntity> results = alertEventMapper.selectByQuery(AlertEventQuery.ofTenant(
        "no-such-tenant-" + BatchDateTimeSupport.utcEpochMillis(), new PageRequest(1, 10)));

    assertThat(results).isEmpty();
  }

  @Test
  @DisplayName("按等级过滤: 只返回严重等级的一条,标题与写入一致")
  void shouldPersistAndQueryAlertEventBySeverity() {
    String tenantId = "t-alert-" + BatchDateTimeSupport.utcEpochMillis();
    insertAlertEvent(tenantId, "SLA_BREACH", "CRITICAL", "OPEN", "Job SLA exceeded");
    insertAlertEvent(tenantId, "SLA_BREACH", "WARN", "OPEN", "File latency exceeded");

    List<AlertEventEntity> highAlerts = alertEventMapper.selectByQuery(
        AlertEventQuery.ofSeverity(tenantId, "CRITICAL", new PageRequest(1, 10)));

    assertThat(highAlerts).hasSize(1);
    assertThat(highAlerts.get(0).getSeverity()).isEqualTo("CRITICAL");
    assertThat(highAlerts.get(0).getTitle()).isEqualTo("Job SLA exceeded");
  }

  @Test
  @DisplayName("按状态过滤: 只返回未关闭的一条")
  void shouldFilterAlertsByStatus() {
    String tenantId = "t-alert-status-" + BatchDateTimeSupport.utcEpochMillis();
    insertAlertEvent(tenantId, "DISK_USAGE", "WARN", "OPEN", "Disk 85% used");
    insertAlertEvent(tenantId, "DISK_USAGE", "WARN", "CLOSED", "Disk resolved");

    List<AlertEventEntity> openAlerts = alertEventMapper.selectByQuery(
        AlertEventQuery.ofStatus(tenantId, "OPEN", new PageRequest(1, 10)));

    assertThat(openAlerts).hasSize(1);
    assertThat(openAlerts.get(0).getStatus()).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("按告警类型过滤: 只返回该类型的一条")
  void shouldFilterAlertsByAlertType() {
    String tenantId = "t-alert-type-" + BatchDateTimeSupport.utcEpochMillis();
    insertAlertEvent(tenantId, "SLA_BREACH", "CRITICAL", "OPEN", "SLA alert");
    insertAlertEvent(tenantId, "FILE_STUCK", "WARN", "OPEN", "File stuck alert");

    List<AlertEventEntity> slaAlerts = alertEventMapper.selectByQuery(
        AlertEventQuery.ofAlertType(tenantId, "SLA_BREACH", new PageRequest(1, 10)));

    assertThat(slaAlerts).hasSize(1);
    assertThat(slaAlerts.get(0).getAlertType()).isEqualTo("SLA_BREACH");
  }

  @Test
  @DisplayName("分页上限: 写入五条,首页只返回三条")
  void shouldRespectLimit() {
    String tenantId = "t-alert-limit-" + BatchDateTimeSupport.utcEpochMillis();
    for (int i = 0; i < 5; i++) {
      insertAlertEvent(tenantId, "SLA_BREACH", "CRITICAL", "OPEN", "Alert " + i);
    }

    List<AlertEventEntity> results =
        alertEventMapper.selectByQuery(AlertEventQuery.ofTenant(tenantId, new PageRequest(1, 3)));

    assertThat(results).hasSize(3);
  }

  @Test
  @DisplayName("按最近出现时间范围过滤: 只命中窗口内的一条,计数一致")
  void shouldFilterAlertsByLastSeenTimeRange() {
    String tenantId = "t-alert-time-" + BatchDateTimeSupport.utcEpochMillis();
    Instant now = BatchDateTimeSupport.utcNow();
    insertAlertEventAt(tenantId, "SLA_BREACH", "CRITICAL", "OPEN", "old", now.minusSeconds(864000));
    insertAlertEventAt(
        tenantId, "SLA_BREACH", "CRITICAL", "OPEN", "recent", now.minusSeconds(3600));

    // fromTime = 2 天前 → 只命中 recent
    AlertEventQuery query = AlertEventQuery.ofLastSeenRange(
        tenantId, now.minusSeconds(172800), null, new PageRequest(1, 10));

    List<AlertEventEntity> rows = alertEventMapper.selectByQuery(query);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getTitle()).isEqualTo("recent");
    assertThat(alertEventMapper.countByQuery(query)).isEqualTo(1);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void insertAlertEvent(
      String tenantId, String alertType, String severity, String status, String title) {
    insertAlertEventAt(tenantId, alertType, severity, status, title, BatchDateTimeSupport.utcNow());
  }

  private void insertAlertEventAt(
      String tenantId,
      String alertType,
      String severity,
      String status,
      String title,
      Instant lastSeenAt) {
    jdbcTemplate.update(
        """
        INSERT INTO batch.alert_event
          (tenant_id, service_name, alert_type, severity, title, detail_json, dedup_fingerprint,
           occurrence_count, first_seen_at, last_seen_at, status, created_at, updated_at)
        VALUES (?, 'batch-orchestrator', ?, ?, ?, '{}', ?,
                1, ?, ?, ?, now(), now())
        """,
        tenantId,
        alertType,
        severity,
        title,
        tenantId + ":" + alertType + ":" + System.nanoTime(),
        Timestamp.from(lastSeenAt),
        Timestamp.from(lastSeenAt),
        status);
  }
}
