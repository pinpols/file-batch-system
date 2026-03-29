package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.entity.DeadLetterTaskEntity;
import com.example.batch.console.domain.query.DeadLetterTaskQuery;
import com.example.batch.console.mapper.DeadLetterTaskMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.common.model.PageRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test: console DeadLetterTaskMapper query against real DB.
 */
@SpringBootTest(
        classes = BatchConsoleApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DeadLetterQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DeadLetterTaskMapper deadLetterTaskMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldReturnEmptyWhenNoDeadLettersExist() {
        List<DeadLetterTaskEntity> results = deadLetterTaskMapper.selectByQuery(
                new DeadLetterTaskQuery("no-such-tenant-" + System.currentTimeMillis(),
                        null, null, null, new PageRequest(1, 10)));

        assertThat(results).isEmpty();
    }

    @Test
    void shouldQueryDeadLettersByTenantAndReplayStatus() {
        String tenantId = "t-dlq-" + System.currentTimeMillis();
        insertDeadLetter(tenantId, "JOB_PARTITION", 100L, "NEW", "trace-dlq-001");
        insertDeadLetter(tenantId, "JOB_PARTITION", 101L, "FAILED", "trace-dlq-002");
        insertDeadLetter(tenantId, "JOB_PARTITION", 102L, "SUCCESS", "trace-dlq-003");

        List<DeadLetterTaskEntity> newDlq = deadLetterTaskMapper.selectByQuery(
                new DeadLetterTaskQuery(tenantId, null, "NEW", null, new PageRequest(1, 10)));

        assertThat(newDlq).hasSize(1);
        assertThat(newDlq.get(0).getReplayStatus()).isEqualTo("NEW");
        assertThat(newDlq.get(0).getSourceId()).isEqualTo(100L);
    }

    @Test
    void shouldQueryDeadLettersBySourceType() {
        String tenantId = "t-dlq-type-" + System.currentTimeMillis();
        insertDeadLetter(tenantId, "JOB_PARTITION", 200L, "NEW", "trace-type-001");
        insertDeadLetter(tenantId, "JOB_PARTITION", 201L, "NEW", "trace-type-002");

        List<DeadLetterTaskEntity> partitionDlq = deadLetterTaskMapper.selectByQuery(
                new DeadLetterTaskQuery(tenantId, "JOB_PARTITION", null, null, new PageRequest(1, 10)));

        assertThat(partitionDlq).hasSize(2);
        assertThat(partitionDlq).allMatch(d -> "JOB_PARTITION".equals(d.getSourceType()));
    }

    @Test
    void shouldQueryDeadLettersByTraceId() {
        String tenantId = "t-dlq-trace-" + System.currentTimeMillis();
        String uniqueTrace = "unique-trace-dlq-" + System.currentTimeMillis();
        insertDeadLetter(tenantId, "JOB_PARTITION", 300L, "NEW", uniqueTrace);

        List<DeadLetterTaskEntity> results = deadLetterTaskMapper.selectByQuery(
                new DeadLetterTaskQuery(tenantId, null, null, uniqueTrace, new PageRequest(1, 10)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTraceId()).isEqualTo(uniqueTrace);
    }

    @Test
    void shouldReturnAllDeadLettersForTenantWithNoFilter() {
        String tenantId = "t-dlq-all-" + System.currentTimeMillis();
        insertDeadLetter(tenantId, "JOB_PARTITION", 400L, "NEW", "trace-all-1");
        insertDeadLetter(tenantId, "JOB_PARTITION", 401L, "FAILED", "trace-all-2");
        insertDeadLetter(tenantId, "JOB_PARTITION", 402L, "SUCCESS", "trace-all-3");

        List<DeadLetterTaskEntity> all = deadLetterTaskMapper.selectByQuery(
                new DeadLetterTaskQuery(tenantId, null, null, null, new PageRequest(1, 10)));

        assertThat(all).hasSize(3);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void insertDeadLetter(String tenantId, String sourceType, Long sourceId,
                                   String replayStatus, String traceId) {
        jdbcTemplate.update("""
                INSERT INTO batch.dead_letter_task
                  (tenant_id, source_type, source_id, dead_letter_reason,
                   payload_ref, replay_status, replay_count, trace_id,
                   created_at, updated_at)
                VALUES (?, ?, ?, 'ERROR: parse failed',
                        ?, ?, 0, ?,
                        now(), now())
                """,
                tenantId, sourceType, sourceId,
                tenantId + ":" + sourceId,
                replayStatus, traceId);
    }
}
