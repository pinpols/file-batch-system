package io.github.pinpols.batch.orchestrator.integration;

import io.github.pinpols.batch.orchestrator.domain.param.AlertEventQueryParam;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.mapper.AlertEventMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("告警升级全局扫描的租户边界")
class AlertEscalationTenantIsolationIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private AlertEventMapper alertEventMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("全局扫描读取多个租户告警,逐行升级仍必须匹配所属租户")
  void shouldScanAcrossTenantsAndFenceEscalationByTenant() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenantA = "alert-escalation-a-" + suffix;
    String tenantB = "alert-escalation-b-" + suffix;
    long alertA = insertOverdueOpenAlert(tenantA, suffix + "-a");
    long alertB = insertOverdueOpenAlert(tenantB, suffix + "-b");

    try {
      List<AlertEventEntity> candidates = alertEventMapper.selectOverdueForEscalation(1, 3, 1_000);

      assertThat(candidates).anySatisfy(alert -> assertThat(alert.getId()).isEqualTo(alertA));
      assertThat(candidates).anySatisfy(alert -> assertThat(alert.getId()).isEqualTo(alertB));
      assertThat(candidates.stream()
              .filter(alert -> alert.getId().equals(alertA))
              .map(AlertEventEntity::getTenantId))
          .containsExactly(tenantA);
      assertThat(candidates.stream()
              .filter(alert -> alert.getId().equals(alertB))
              .map(AlertEventEntity::getTenantId))
          .containsExactly(tenantB);
      assertThat(alertEventMapper.selectByQuery(AlertEventQueryParam.builder().tenantId(tenantA).severity(null).status(null).alertType(null).limit(20).build()))
          .extracting(AlertEventEntity::getTenantId)
          .contains(tenantA)
          .doesNotContain(tenantB);
      List<AlertEventEntity> openAlerts = alertEventMapper.selectOpenForReemit(1_000);
      assertThat(openAlerts.stream()
              .filter(alert -> alert.getId().equals(alertA))
              .map(AlertEventEntity::getTenantId))
          .containsExactly(tenantA);
      assertThat(openAlerts.stream()
              .filter(alert -> alert.getId().equals(alertB))
              .map(AlertEventEntity::getTenantId))
          .containsExactly(tenantB);

      assertThat(alertEventMapper.markEscalated(alertA, tenantB, 0)).isZero();
      assertThat(escalationTier(alertA, tenantA)).isZero();
      assertThat(alertEventMapper.markEscalated(alertA, tenantA, 0)).isOne();
      assertThat(escalationTier(alertA, tenantA)).isEqualTo(1);
      assertThat(escalationTier(alertB, tenantB)).isZero();
    } finally {
      String deleteTenantAlertsSql = """
          delete from batch.alert_event where tenant_id in (?, ?)
          """;
      jdbcTemplate.update(deleteTenantAlertsSql, tenantA, tenantB);
    }
  }

  private long insertOverdueOpenAlert(String tenantId, String fingerprint) {
    String insertOverdueOpenAlertSql = """
        insert into batch.alert_event (
          tenant_id, service_name, alert_type, severity, title, dedup_fingerprint,
          status, last_seen_at
        ) values (?, 'batch-orchestrator', 'R20_TEST', 'WARN', 'tenant isolation', ?, 'OPEN',
                  current_timestamp - interval '1 day')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertOverdueOpenAlertSql, Long.class, tenantId, fingerprint);
  }

  private int escalationTier(long alertId, String tenantId) {
    String escalationTierSql = """
        select escalation_tier from batch.alert_event where id = ? and tenant_id = ?
        """;
    return jdbcTemplate.queryForObject(
        escalationTierSql,
        Integer.class,
        alertId,
        tenantId);
  }
}
