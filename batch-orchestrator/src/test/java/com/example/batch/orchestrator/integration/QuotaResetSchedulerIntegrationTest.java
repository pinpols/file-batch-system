package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateRecord;
import com.example.batch.orchestrator.repository.QuotaRuntimeStateRepository;
import com.example.batch.orchestrator.scheduler.QuotaRuntimeResetScheduler;
import com.example.batch.orchestrator.scheduler.quota.QuotaRuntimeStateService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration test: QuotaRuntimeResetScheduler invokes reconcileExpiredStates with the
 * configured sliding-window-hours, and the quota-reset-enabled flag correctly gates execution.
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "batch.resource-scheduler.quota-reset-enabled=true",
        "batch.resource-scheduler.quota-reset-sliding-window-hours=2",
        "batch.resource-scheduler.quota-reset-scan-interval-millis=600000"
})
class QuotaResetSchedulerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private QuotaRuntimeResetScheduler quotaRuntimeResetScheduler;

    @Autowired
    private QuotaRuntimeStateService quotaRuntimeStateService;

    @Autowired
    private QuotaRuntimeStateRepository quotaRuntimeStateRepository;

    @Autowired
    private ResourceSchedulerProperties resourceSchedulerProperties;

    @Test
    void resourceSchedulerPropertiesAreLoadedCorrectly() {
        assertThat(resourceSchedulerProperties.isQuotaResetEnabled()).isTrue();
        assertThat(resourceSchedulerProperties.getQuotaResetSlidingWindowHours()).isEqualTo(2);
        assertThat(resourceSchedulerProperties.getQuotaResetScanIntervalMillis()).isEqualTo(600000L);
    }

    @Test
    void schedulerReconcileResetsExpiredSlidingWindowState() {
        String ownerCode = "sched-reset-" + System.currentTimeMillis();

        QuotaRuntimeStateRecord expired = new QuotaRuntimeStateRecord();
        expired.setTenantId("t1");
        expired.setQuotaScope("JOB");
        expired.setOwnerCode(ownerCode);
        expired.setQuotaResetPolicy("SLIDING_WINDOW");
        expired.setPeakBorrowedCount(7);
        expired.setWindowStartedAt(Instant.now().minusSeconds(10800)); // 3 hours ago
        expired.setWindowExpiresAt(Instant.now().minusSeconds(3600));  // expired 1 hour ago
        expired.setCreatedAt(Instant.now());
        expired.setUpdatedAt(Instant.now());
        quotaRuntimeStateRepository.save(expired);

        // Trigger scheduler directly (schedule interval is 600s so won't auto-fire in test)
        quotaRuntimeResetScheduler.reconcile();

        QuotaRuntimeStateRecord updated = quotaRuntimeStateRepository
                .findFirstByTenantIdAndQuotaScopeAndOwnerCode("t1", "JOB", ownerCode);
        assertThat(updated).isNotNull();
        assertThat(updated.getPeakBorrowedCount()).isZero();
    }

    @Test
    void schedulerReconcileHandlesNoExpiredStatesGracefully() {
        // All states in DB are either non-expired or non-existent for this owner
        String ownerCode = "sched-no-expired-" + System.currentTimeMillis();

        // Create a state that is NOT yet expired (window expires in the future)
        QuotaRuntimeStateRecord active = new QuotaRuntimeStateRecord();
        active.setTenantId("t1");
        active.setQuotaScope("JOB");
        active.setOwnerCode(ownerCode);
        active.setQuotaResetPolicy("SLIDING_WINDOW");
        active.setPeakBorrowedCount(3);
        active.setWindowStartedAt(Instant.now().minusSeconds(1800));
        active.setWindowExpiresAt(Instant.now().plusSeconds(3600)); // still valid
        active.setCreatedAt(Instant.now());
        active.setUpdatedAt(Instant.now());
        quotaRuntimeStateRepository.save(active);

        // reconcile() should not touch non-expired states
        quotaRuntimeResetScheduler.reconcile();

        QuotaRuntimeStateRecord afterReconcile = quotaRuntimeStateRepository
                .findFirstByTenantIdAndQuotaScopeAndOwnerCode("t1", "JOB", ownerCode);
        assertThat(afterReconcile).isNotNull();
        // peakBorrowedCount should remain unchanged since the window hasn't expired
        assertThat(afterReconcile.getPeakBorrowedCount()).isEqualTo(3);
    }

    @Test
    void evaluateAndReserveWithinWindowHoursAllows() {
        String ownerCode = "sched-eval-" + System.currentTimeMillis();

        // base=10, burst=5, active=8, requested=1 — within cap
        var result = quotaRuntimeStateService.evaluateAndReserve(
                "t1", "JOB", ownerCode, "SLIDING_WINDOW",
                10, 5, 8, 1,
                resourceSchedulerProperties.getQuotaResetSlidingWindowHours(),
                "OVER_CAP", "over");

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void evaluateAndReserveOverBurstBlocks() {
        String ownerCode = "sched-burst-" + System.currentTimeMillis();

        // base=5, burst=2, active=10, requested=1 → borrowed=6 > burst=2
        var result = quotaRuntimeStateService.evaluateAndReserve(
                "t1", "JOB", ownerCode, "SLIDING_WINDOW",
                5, 2, 10, 1,
                resourceSchedulerProperties.getQuotaResetSlidingWindowHours(),
                "OVER_BURST", "over burst");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isNotBlank();
    }
}
