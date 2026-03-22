package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateRecord;
import com.example.batch.orchestrator.repository.QuotaRuntimeStateRepository;
import com.example.batch.orchestrator.scheduler.ResourceCheck;
import com.example.batch.orchestrator.scheduler.quota.QuotaRuntimeStateService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test: QuotaRuntimeStateService against real DB.
 * Covers SLIDING_WINDOW and CALENDAR_DAY reset, peak tracking, and reconcile.
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QuotaRuntimeStateIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private QuotaRuntimeStateService quotaRuntimeStateService;

    @Autowired
    private QuotaRuntimeStateRepository quotaRuntimeStateRepository;

    @Test
    void shouldAllowWhenWithinBaseCapNoBurstNeeded() {
        ResourceCheck result = quotaRuntimeStateService.evaluateAndReserve(
                "t1", "JOB", "quota-test-basic-" + System.currentTimeMillis(),
                "NONE", 10, 0, 5, 1, 24, "OVER_CAP", "over");

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldBlockWhenOverBaseCapNoBurst() {
        ResourceCheck result = quotaRuntimeStateService.evaluateAndReserve(
                "t1", "JOB", "quota-test-block-" + System.currentTimeMillis(),
                "NONE", 5, 0, 5, 1, 24, "OVER_CAP", "over cap");

        assertThat(result.allowed()).isFalse();
    }

    @Test
    void shouldCreateNewStateRecordForSlidingWindow() {
        String ownerCode = "sw-test-" + System.currentTimeMillis();

        ResourceCheck result = quotaRuntimeStateService.evaluateAndReserve(
                "t1", "JOB", ownerCode, "SLIDING_WINDOW", 5, 10, 7, 1, 2, "OVER", "over");

        assertThat(result.allowed()).isTrue();

        QuotaRuntimeStateRecord state = quotaRuntimeStateRepository
                .findFirstByTenantIdAndQuotaScopeAndOwnerCode("t1", "JOB", ownerCode);
        assertThat(state).isNotNull();
        assertThat(state.getWindowStartedAt()).isNotNull();
        assertThat(state.getWindowExpiresAt()).isNotNull();
        assertThat(state.getWindowExpiresAt()).isAfter(state.getWindowStartedAt());
    }

    @Test
    void shouldTrackPeakBorrowedCountForSlidingWindow() {
        String ownerCode = "sw-peak-" + System.currentTimeMillis();

        // First reservation: active=8, base=5, requested=2 → borrowed=5
        quotaRuntimeStateService.evaluateAndReserve(
                "t1", "JOB", ownerCode, "SLIDING_WINDOW", 5, 10, 8, 2, 2, "OVER", "over");

        QuotaRuntimeStateRecord state = quotaRuntimeStateRepository
                .findFirstByTenantIdAndQuotaScopeAndOwnerCode("t1", "JOB", ownerCode);
        assertThat(state).isNotNull();
        assertThat(state.getPeakBorrowedCount()).isGreaterThan(0);
    }

    @Test
    void shouldBlockWhenBorrowedExceedsBurst() {
        String ownerCode = "sw-burst-" + System.currentTimeMillis();

        // burst=3, active=10, base=5, requested=1 → borrowed=6 > burst=3
        ResourceCheck result = quotaRuntimeStateService.evaluateAndReserve(
                "t1", "JOB", ownerCode, "SLIDING_WINDOW", 5, 3, 10, 1, 2, "OVER_BURST", "over burst");

        assertThat(result.allowed()).isFalse();
    }

    @Test
    void shouldCreateNewStateRecordForCalendarDay() {
        String ownerCode = "cd-test-" + System.currentTimeMillis();

        ResourceCheck result = quotaRuntimeStateService.evaluateAndReserve(
                "t1", "JOB", ownerCode, "CALENDAR_DAY", 5, 10, 7, 1, 24, "OVER", "over");

        assertThat(result.allowed()).isTrue();

        QuotaRuntimeStateRecord state = quotaRuntimeStateRepository
                .findFirstByTenantIdAndQuotaScopeAndOwnerCode("t1", "JOB", ownerCode);
        assertThat(state).isNotNull();
        assertThat(state.getWindowStartedAt()).isNotNull();
    }

    @Test
    void shouldDescribeExistingState() {
        String ownerCode = "describe-test-" + System.currentTimeMillis();

        // Create state first
        quotaRuntimeStateService.evaluateAndReserve(
                "t1", "JOB", ownerCode, "SLIDING_WINDOW", 5, 10, 7, 2, 2, "OVER", "over");

        QuotaRuntimeStateService.QuotaRuntimeSnapshot snapshot = quotaRuntimeStateService.describe(
                "t1", "JOB", ownerCode, "SLIDING_WINDOW", 10, 2);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.quotaResetPolicy()).isEqualTo("SLIDING_WINDOW");
        assertThat(snapshot.burstLimit()).isEqualTo(10);
        assertThat(snapshot.peakBorrowedCount()).isGreaterThanOrEqualTo(0);
        assertThat(snapshot.remainingBurst()).isLessThanOrEqualTo(10);
    }

    @Test
    void shouldReconcileExpiredSlidingWindowState() {
        String ownerCode = "reconcile-sw-" + System.currentTimeMillis();

        // Insert an already-expired state directly
        QuotaRuntimeStateRecord expiredState = new QuotaRuntimeStateRecord();
        expiredState.setTenantId("t1");
        expiredState.setQuotaScope("JOB");
        expiredState.setOwnerCode(ownerCode);
        expiredState.setQuotaResetPolicy("SLIDING_WINDOW");
        expiredState.setPeakBorrowedCount(5);
        expiredState.setWindowStartedAt(Instant.now().minusSeconds(7200));
        expiredState.setWindowExpiresAt(Instant.now().minusSeconds(3600)); // expired
        expiredState.setCreatedAt(Instant.now());
        expiredState.setUpdatedAt(Instant.now());
        quotaRuntimeStateRepository.save(expiredState);

        quotaRuntimeStateService.reconcileExpiredStates(2);

        // After reconcile the peak should be reset
        QuotaRuntimeStateRecord updated = quotaRuntimeStateRepository
                .findFirstByTenantIdAndQuotaScopeAndOwnerCode("t1", "JOB", ownerCode);
        assertThat(updated).isNotNull();
        assertThat(updated.getPeakBorrowedCount()).isZero();
    }

    @Test
    void shouldFindExpiredStatesViaRepository() {
        String ownerCode = "find-expired-" + System.currentTimeMillis();

        QuotaRuntimeStateRecord state = new QuotaRuntimeStateRecord();
        state.setTenantId("t1");
        state.setQuotaScope("JOB");
        state.setOwnerCode(ownerCode);
        state.setQuotaResetPolicy("SLIDING_WINDOW");
        state.setPeakBorrowedCount(3);
        state.setWindowStartedAt(Instant.now().minusSeconds(3700));
        state.setWindowExpiresAt(Instant.now().minusSeconds(100)); // expired
        state.setCreatedAt(Instant.now());
        state.setUpdatedAt(Instant.now());
        quotaRuntimeStateRepository.save(state);

        List<QuotaRuntimeStateRecord> expired = quotaRuntimeStateRepository.findExpired(Instant.now());

        boolean found = expired.stream().anyMatch(r -> ownerCode.equals(r.getOwnerCode()));
        assertThat(found).isTrue();
    }
}
