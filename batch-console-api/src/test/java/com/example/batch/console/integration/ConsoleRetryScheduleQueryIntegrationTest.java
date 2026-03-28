package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.entity.RetryScheduleEntity;
import com.example.batch.console.domain.query.RetryScheduleQuery;
import com.example.batch.console.mapper.RetryScheduleMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test: console RetryScheduleMapper query against real DB.
 */
@SpringBootTest(
        classes = BatchConsoleApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ConsoleRetryScheduleQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RetryScheduleMapper retryScheduleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldReturnEmptyWhenNoRetrySchedulesExist() {
        List<RetryScheduleEntity> results = retryScheduleMapper.selectByQuery(
                new RetryScheduleQuery("no-such-tenant-" + System.currentTimeMillis(),
                        null, null, null));

        assertThat(results).isEmpty();
    }

    @Test
    void shouldQueryRetrySchedulesByStatus() {
        String tenantId = "t-retry-" + System.currentTimeMillis();
        insertRetrySchedule(tenantId, "JOB_PARTITION", 100L, "FIXED", "WAITING", 1);
        insertRetrySchedule(tenantId, "JOB_PARTITION", 101L, "FIXED", "SUCCESS", 2);
        insertRetrySchedule(tenantId, "JOB_PARTITION", 102L, "FIXED", "FAILED", 1);

        List<RetryScheduleEntity> waiting = retryScheduleMapper.selectByQuery(
                new RetryScheduleQuery(tenantId, null, null, "WAITING"));

        assertThat(waiting).hasSize(1);
        assertThat(waiting.get(0).getRetryStatus()).isEqualTo("WAITING");
    }

    @Test
    void shouldQueryRetrySchedulesByRetryPolicy() {
        String tenantId = "t-retry-policy-" + System.currentTimeMillis();
        insertRetrySchedule(tenantId, "JOB_PARTITION", 200L, "FIXED", "WAITING", 1);
        insertRetrySchedule(tenantId, "JOB_PARTITION", 201L, "EXPONENTIAL", "WAITING", 1);

        List<RetryScheduleEntity> exponential = retryScheduleMapper.selectByQuery(
                new RetryScheduleQuery(tenantId, null, "EXPONENTIAL", null));

        assertThat(exponential).hasSize(1);
        assertThat(exponential.get(0).getRetryPolicy()).isEqualTo("EXPONENTIAL");
    }

    @Test
    void shouldQueryRetrySchedulesByRelatedType() {
        String tenantId = "t-retry-type-" + System.currentTimeMillis();
        insertRetrySchedule(tenantId, "JOB_PARTITION", 300L, "FIXED", "WAITING", 1);
        insertRetrySchedule(tenantId, "JOB_PARTITION", 301L, "FIXED", "WAITING", 1);

        List<RetryScheduleEntity> partitionRetries = retryScheduleMapper.selectByQuery(
                new RetryScheduleQuery(tenantId, "JOB_PARTITION", null, null));

        assertThat(partitionRetries).hasSize(2);
        assertThat(partitionRetries).allMatch(r -> "JOB_PARTITION".equals(r.getRelatedType()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void insertRetrySchedule(String tenantId, String relatedType, Long relatedId,
                                      String retryPolicy, String retryStatus, int retryCount) {
        jdbcTemplate.update("""
                INSERT INTO batch.retry_schedule
                  (tenant_id, related_type, related_id, retry_policy, retry_count, max_retry_count,
                   next_retry_at, retry_status, dedup_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 3,
                        ?, ?, ?,
                        now(), now())
                """,
                tenantId, relatedType, relatedId, retryPolicy, retryCount,
                Timestamp.from(Instant.now().plusSeconds(60)),
                retryStatus,
                tenantId + ":" + relatedId + ":" + retryCount + ":" + System.nanoTime());
    }
}
