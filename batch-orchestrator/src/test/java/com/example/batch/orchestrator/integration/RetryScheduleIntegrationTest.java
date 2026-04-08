package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.RetryScheduleStatus;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.domain.entity.RetryScheduleEntity;
import com.example.batch.orchestrator.domain.query.RetryScheduleQuery;
import com.example.batch.orchestrator.mapper.RetryScheduleMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 集成测试：RetryScheduleMapper 在真实数据库上的持久化和查询。
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RetryScheduleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RetryScheduleMapper retryScheduleMapper;

    @Test
    void shouldInsertAndSelectRetrySchedule() {
        RetryScheduleEntity entity = waitingRetry("t1", 100L, "FIXED", 1, 3);
        retryScheduleMapper.insert(entity);

        assertThat(entity.getId()).isNotNull();

        RetryScheduleEntity loaded = retryScheduleMapper.selectById(entity.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getTenantId()).isEqualTo("t1");
        assertThat(loaded.getRelatedId()).isEqualTo(100L);
        assertThat(loaded.getRetryStatus()).isEqualTo(RetryScheduleStatus.WAITING.code());
    }

    @Test
    void shouldFindDueRetrySchedulesViaSelectByQuery() {
        String dedupKey = "t1:due-test:" + System.currentTimeMillis();
        RetryScheduleEntity entity = waitingRetry("t1", 200L, "FIXED", 1, 3);
        entity.setDedupKey(dedupKey);
        entity.setNextRetryAt(Instant.now().minusSeconds(60)); // already due
        retryScheduleMapper.insert(entity);

        List<RetryScheduleEntity> due = retryScheduleMapper.selectByQuery(new RetryScheduleQuery(
                "t1", RetryScheduleStatus.WAITING.code(), Instant.now(), 100));

        assertThat(due).isNotEmpty();
        boolean found = due.stream().anyMatch(r -> dedupKey.equals(r.getDedupKey()));
        assertThat(found).isTrue();
    }

    @Test
    void shouldNotReturnFutureSchedulesAsDue() {
        String dedupKey = "t1:future-test:" + System.currentTimeMillis();
        RetryScheduleEntity entity = waitingRetry("t1", 300L, "FIXED", 1, 3);
        entity.setDedupKey(dedupKey);
        entity.setNextRetryAt(Instant.now().plusSeconds(3600)); // not yet due
        retryScheduleMapper.insert(entity);

        List<RetryScheduleEntity> due = retryScheduleMapper.selectByQuery(new RetryScheduleQuery(
                "t1", RetryScheduleStatus.WAITING.code(), Instant.now(), 100));

        boolean found = due.stream().anyMatch(r -> dedupKey.equals(r.getDedupKey()));
        assertThat(found).isFalse();
    }

    @Test
    void shouldMarkRetryScheduleAsRunning() {
        RetryScheduleEntity entity = waitingRetry("t1", 400L, "FIXED", 2, 3);
        entity.setDedupKey("t1:mark-running:" + System.currentTimeMillis());
        entity.setNextRetryAt(Instant.now().minusSeconds(10));
        retryScheduleMapper.insert(entity);

        int updated = retryScheduleMapper.markRunning(entity.getId(), RetryScheduleStatus.WAITING.code(), RetryScheduleStatus.RUNNING.code());

        assertThat(updated).isEqualTo(1);
    }

    @Test
    void shouldNotMarkRunningIfStatusAlreadyRunning() {
        RetryScheduleEntity entity = waitingRetry("t1", 500L, "FIXED", 2, 3);
        entity.setDedupKey("t1:no-double-run:" + System.currentTimeMillis());
        entity.setNextRetryAt(Instant.now().minusSeconds(10));
        retryScheduleMapper.insert(entity);

        retryScheduleMapper.markRunning(entity.getId(), RetryScheduleStatus.WAITING.code(), RetryScheduleStatus.RUNNING.code());
        // 第二次尝试应失败（通过 fromStatus 检查实现乐观锁）
        int second = retryScheduleMapper.markRunning(entity.getId(), RetryScheduleStatus.WAITING.code(), RetryScheduleStatus.RUNNING.code());

        assertThat(second).isZero();
    }

    @Test
    void shouldMarkRetryScheduleAsSuccess() {
        RetryScheduleEntity entity = waitingRetry("t1", 600L, "FIXED", 1, 3);
        entity.setDedupKey("t1:mark-success:" + System.currentTimeMillis());
        entity.setNextRetryAt(Instant.now().minusSeconds(10));
        retryScheduleMapper.insert(entity);
        retryScheduleMapper.markRunning(entity.getId(), RetryScheduleStatus.WAITING.code(), RetryScheduleStatus.RUNNING.code());

        int updated = retryScheduleMapper.markSuccess(entity.getId(), RetryScheduleStatus.SUCCESS.code());

        assertThat(updated).isEqualTo(1);
        RetryScheduleEntity loaded = retryScheduleMapper.selectById(entity.getId());
        assertThat(loaded.getRetryStatus()).isEqualTo(RetryScheduleStatus.SUCCESS.code());
    }

    @Test
    void shouldMarkRetryScheduleAsFailed() {
        RetryScheduleEntity entity = waitingRetry("t1", 700L, "FIXED", 1, 3);
        entity.setDedupKey("t1:mark-failed:" + System.currentTimeMillis());
        entity.setNextRetryAt(Instant.now().minusSeconds(10));
        retryScheduleMapper.insert(entity);
        retryScheduleMapper.markRunning(entity.getId(), RetryScheduleStatus.WAITING.code(), RetryScheduleStatus.RUNNING.code());

        int updated = retryScheduleMapper.markFailed(
                entity.getId(),
                RetryScheduleStatus.FAILED.code(),
                "DISPATCH_FAILED",
                "connection refused",
                Instant.now().plusSeconds(120)
        );

        assertThat(updated).isEqualTo(1);
        RetryScheduleEntity loaded = retryScheduleMapper.selectById(entity.getId());
        assertThat(loaded.getRetryStatus()).isEqualTo(RetryScheduleStatus.FAILED.code());
        assertThat(loaded.getLastErrorCode()).isEqualTo("DISPATCH_FAILED");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RetryScheduleEntity waitingRetry(String tenantId, Long relatedId,
                                                     String retryPolicy, int retryCount, int maxRetryCount) {
        RetryScheduleEntity e = new RetryScheduleEntity();
        e.setTenantId(tenantId);
        e.setRelatedType("JOB_PARTITION");
        e.setRelatedId(relatedId);
        e.setRetryPolicy(retryPolicy);
        e.setRetryCount(retryCount);
        e.setMaxRetryCount(maxRetryCount);
        e.setNextRetryAt(Instant.now().minusSeconds(30));
        e.setRetryStatus(RetryScheduleStatus.WAITING.code());
        e.setDedupKey(tenantId + ":" + relatedId + ":" + retryCount);
        return e;
    }
}
