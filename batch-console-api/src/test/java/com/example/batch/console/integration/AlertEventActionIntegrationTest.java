package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.application.ConsoleAlertApplicationService;
import com.example.batch.console.mapper.AlertEventMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.AlertActionRequest;
import com.example.batch.console.web.response.ConsoleAlertActionResponse;
import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：告警事件操作通过告警应用服务更新状态。
 */
@SpringBootTest(
        classes = BatchConsoleApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AlertEventActionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ConsoleAlertApplicationService alertApplicationService;

    @Autowired
    private AlertEventMapper alertEventMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldAckAlert() {
        String tenantId = "t-alert-ack-" + System.currentTimeMillis();
        long alertId = insertAlertEvent(tenantId, "FILE_STUCK", "WARN", "OPEN", "File stalled");

        ConsoleAlertActionResponse response = alertApplicationService.ack(alertId, alertRequest(tenantId), "idem-1");

        assertThat(response.status()).isEqualTo("ACKED");
        AlertEventEntity entity = alertEventMapper.selectById(tenantId, alertId);
        assertThat(entity).isNotNull();
        assertThat(entity.getStatus()).isEqualTo("ACKED");
    }

    @Test
    void shouldSilenceAlert() {
        String tenantId = "t-alert-silence-" + System.currentTimeMillis();
        long alertId = insertAlertEvent(tenantId, "SLA_BREACH", "CRITICAL", "OPEN", "SLA breach");

        ConsoleAlertActionResponse response = alertApplicationService.silence(alertId, alertRequest(tenantId), "idem-2");

        assertThat(response.status()).isEqualTo("SUPPRESSED");
        AlertEventEntity entity = alertEventMapper.selectById(tenantId, alertId);
        assertThat(entity).isNotNull();
        assertThat(entity.getStatus()).isEqualTo("SUPPRESSED");
    }

    @Test
    void shouldCloseAlert() {
        String tenantId = "t-alert-close-" + System.currentTimeMillis();
        long alertId = insertAlertEvent(tenantId, "DISK_USAGE", "ERROR", "OPEN", "Disk issue");

        ConsoleAlertActionResponse response = alertApplicationService.close(alertId, alertRequest(tenantId), "idem-3");

        assertThat(response.status()).isEqualTo("CLOSED");
        AlertEventEntity entity = alertEventMapper.selectById(tenantId, alertId);
        assertThat(entity).isNotNull();
        assertThat(entity.getStatus()).isEqualTo("CLOSED");
    }

    private AlertActionRequest alertRequest(String tenantId) {
        AlertActionRequest request = new AlertActionRequest();
        request.setTenantId(tenantId);
        request.setOperatorId("operator-1");
        request.setReason("manual action");
        return request;
    }

    private long insertAlertEvent(String tenantId, String alertType, String severity, String status, String title) {
        jdbcTemplate.update("""
                INSERT INTO batch.alert_event
                  (tenant_id, service_name, alert_type, severity, title, detail_json, dedup_fingerprint,
                   occurrence_count, first_seen_at, last_seen_at, status, created_at, updated_at)
                VALUES (?, 'batch-orchestrator', ?, ?, ?, '{}', ?,
                        1, ?, ?, ?, now(), now())
                """,
                tenantId, alertType, severity, title,
                tenantId + ":" + alertType + ":" + System.nanoTime(),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), status);
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
