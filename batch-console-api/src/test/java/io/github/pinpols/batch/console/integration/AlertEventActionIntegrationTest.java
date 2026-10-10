package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.contract.request.ops.AlertActionRequest;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleAlertApplicationService;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertActionResponse;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** 集成测试：告警事件操作通过告警应用服务更新状态。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("告警事件操作: 确认,抑制,关闭三个动作的响应与落库状态流转")
class AlertEventActionIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private ConsoleAlertApplicationService alertApplicationService;

  @Autowired
  private AlertEventMapper alertEventMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("确认告警: 响应状态为已确认,且库内记录状态同步更新")
  void shouldAckAlert() {
    String tenantId = "t-alert-ack-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlertEvent(tenantId, "FILE_STUCK", "WARN", "OPEN", "File stalled");

    ConsoleAlertActionResponse response =
        alertApplicationService.ack(alertId, alertRequest(tenantId), "idem-1");

    assertThat(response.status()).isEqualTo("ACKED");
    AlertEventEntity entity = alertEventMapper.selectById(tenantId, alertId);
    assertThat(entity).isNotNull();
    assertThat(entity.getStatus()).isEqualTo("ACKED");
  }

  @Test
  @DisplayName("抑制告警: 响应状态为已抑制,且库内记录状态同步更新")
  void shouldSilenceAlert() {
    String tenantId = "t-alert-silence-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlertEvent(tenantId, "SLA_BREACH", "CRITICAL", "OPEN", "SLA breach");

    ConsoleAlertActionResponse response =
        alertApplicationService.silence(alertId, alertRequest(tenantId), "idem-2");

    assertThat(response.status()).isEqualTo("SUPPRESSED");
    AlertEventEntity entity = alertEventMapper.selectById(tenantId, alertId);
    assertThat(entity).isNotNull();
    assertThat(entity.getStatus()).isEqualTo("SUPPRESSED");
  }

  @Test
  @DisplayName("关闭告警: 响应状态为已关闭,且库内记录状态同步更新")
  void shouldCloseAlert() {
    String tenantId = "t-alert-close-" + BatchDateTimeSupport.utcEpochMillis();
    long alertId = insertAlertEvent(tenantId, "DISK_USAGE", "ERROR", "OPEN", "Disk issue");

    ConsoleAlertActionResponse response =
        alertApplicationService.close(alertId, alertRequest(tenantId), "idem-3");

    assertThat(response.status()).isEqualTo("CLOSED");
    AlertEventEntity entity = alertEventMapper.selectById(tenantId, alertId);
    assertThat(entity).isNotNull();
    assertThat(entity.getStatus()).isEqualTo("CLOSED");
  }

  @Test
  @DisplayName("同一 Alertmanager 告警组仅在最后一条未关闭事件结束后才可解除")
  void shouldCountActivePeersInSameAlertmanagerGroup() {
    String tenantId = "t-alert-group-" + BatchDateTimeSupport.utcEpochMillis();
    long firstId = insertAlertEvent(tenantId, "JOB_RUNNING_TOO_LONG", "WARN", "OPEN", "first");
    long ackedId = insertAlertEvent(tenantId, "JOB_RUNNING_TOO_LONG", "WARN", "ACKED", "acked");
    long suppressedId =
        insertAlertEvent(tenantId, "JOB_RUNNING_TOO_LONG", "WARN", "SUPPRESSED", "suppressed");

    alertApplicationService.close(firstId, alertRequest(tenantId), "idem-group-1");

    assertThat(alertEventMapper.countActiveAlertsInAmGroup(
            tenantId, "batch-orchestrator", "JOB_RUNNING_TOO_LONG", "WARN", firstId))
        .isEqualTo(2);

    alertApplicationService.close(ackedId, alertRequest(tenantId), "idem-group-2");

    assertThat(alertEventMapper.countActiveAlertsInAmGroup(
            tenantId, "batch-orchestrator", "JOB_RUNNING_TOO_LONG", "WARN", ackedId))
        .isEqualTo(1);

    alertApplicationService.close(suppressedId, alertRequest(tenantId), "idem-group-3");

    assertThat(alertEventMapper.countActiveAlertsInAmGroup(
            tenantId, "batch-orchestrator", "JOB_RUNNING_TOO_LONG", "WARN", suppressedId))
        .isZero();
  }

  private AlertActionRequest alertRequest(String tenantId) {
    AlertActionRequest request = new AlertActionRequest();
    request.setTenantId(tenantId);
    request.setOperatorId("operator-1");
    request.setReason("manual action");
    return request;
  }

  private long insertAlertEvent(
      String tenantId, String alertType, String severity, String status, String title) {
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
        Timestamp.from(BatchDateTimeSupport.utcNow()),
        Timestamp.from(BatchDateTimeSupport.utcNow()),
        status);
    Long id = jdbcTemplate.queryForObject("""
            select id
            from batch.alert_event
            where tenant_id = ?
            order by id desc
            limit 1
            """, Long.class, tenantId);
    return id == null ? -1L : id;
  }

  @TestConfiguration
  static class TestTenantGuardConfig {

    @Bean
    @Primary
    ConsoleTenantGuard testTenantGuard() {
      return new ConsoleTenantGuard(null) {
        @Override
        public String resolveTenant(String requestTenantId) {
          return requestTenantId;
        }
      };
    }
  }
}
