package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.console.domain.query.AlertEventQuery;
import com.example.batch.console.mapper.AlertEventMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test: alert_event table persistence and query via AlertEventMapper.
 */
@SpringBootTest(
        classes = BatchConsoleApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AlertEventIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AlertEventMapper alertEventMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldReturnEmptyWhenNoAlertsExist() {
        List<AlertEventEntity> results = alertEventMapper.selectByQuery(
                new AlertEventQuery("no-such-tenant-" + System.currentTimeMillis(), null, null, null, 10));

        assertThat(results).isEmpty();
    }

    @Test
    void shouldPersistAndQueryAlertEventBySeverity() {
        String tenantId = "t-alert-" + System.currentTimeMillis();
        insertAlertEvent(tenantId, "SLA_BREACH", "CRITICAL", "OPEN", "Job SLA exceeded");
        insertAlertEvent(tenantId, "SLA_BREACH", "WARN", "OPEN", "File latency exceeded");

        List<AlertEventEntity> highAlerts = alertEventMapper.selectByQuery(
                new AlertEventQuery(tenantId, "CRITICAL", null, null, 10));

        assertThat(highAlerts).hasSize(1);
        assertThat(highAlerts.get(0).getSeverity()).isEqualTo("CRITICAL");
        assertThat(highAlerts.get(0).getTitle()).isEqualTo("Job SLA exceeded");
    }

    @Test
    void shouldFilterAlertsByStatus() {
        String tenantId = "t-alert-status-" + System.currentTimeMillis();
        insertAlertEvent(tenantId, "DISK_USAGE", "WARN", "OPEN", "Disk 85% used");
        insertAlertEvent(tenantId, "DISK_USAGE", "WARN", "CLOSED", "Disk resolved");

        List<AlertEventEntity> openAlerts = alertEventMapper.selectByQuery(
                new AlertEventQuery(tenantId, null, "OPEN", null, 10));

        assertThat(openAlerts).hasSize(1);
        assertThat(openAlerts.get(0).getStatus()).isEqualTo("OPEN");
    }

    @Test
    void shouldFilterAlertsByAlertType() {
        String tenantId = "t-alert-type-" + System.currentTimeMillis();
        insertAlertEvent(tenantId, "SLA_BREACH", "CRITICAL", "OPEN", "SLA alert");
        insertAlertEvent(tenantId, "FILE_STUCK", "WARN", "OPEN", "File stuck alert");

        List<AlertEventEntity> slaAlerts = alertEventMapper.selectByQuery(
                new AlertEventQuery(tenantId, null, null, "SLA_BREACH", 10));

        assertThat(slaAlerts).hasSize(1);
        assertThat(slaAlerts.get(0).getAlertType()).isEqualTo("SLA_BREACH");
    }

    @Test
    void shouldRespectLimit() {
        String tenantId = "t-alert-limit-" + System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            insertAlertEvent(tenantId, "SLA_BREACH", "CRITICAL", "OPEN", "Alert " + i);
        }

        List<AlertEventEntity> results = alertEventMapper.selectByQuery(
                new AlertEventQuery(tenantId, null, null, null, 3));

        assertThat(results).hasSize(3);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void insertAlertEvent(String tenantId, String alertType, String severity, String status, String title) {
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
    }
}
