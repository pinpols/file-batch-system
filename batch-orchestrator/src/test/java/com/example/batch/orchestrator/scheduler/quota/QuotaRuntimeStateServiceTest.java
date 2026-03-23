package com.example.batch.orchestrator.scheduler.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateRecord;
import com.example.batch.orchestrator.repository.QuotaRuntimeStateRepository;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuotaRuntimeStateServiceTest {

    private QuotaRuntimeStateRepository quotaRuntimeStateRepository;
    private QuotaRuntimeStateService service;

    @BeforeEach
    void setUp() {
        quotaRuntimeStateRepository = mock(QuotaRuntimeStateRepository.class);
        service = new QuotaRuntimeStateService(quotaRuntimeStateRepository);
    }

    // ── evaluateAndReserve — guard conditions ─────────────────────────────────

    @Test
    void shouldAllowWhenTenantIdIsBlank() {
        ResourceCheck result = service.evaluateAndReserve(
                "", "JOB", "job-001", "NONE", 10, 2, 0, 1, 24, "OVER", "over limit");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldAllowWhenBaseCapIsZero() {
        ResourceCheck result = service.evaluateAndReserve(
                "t1", "JOB", "job-001", "NONE", 0, 2, 0, 1, 24, "OVER", "over limit");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldAllowWhenBaseCapIsNegative() {
        ResourceCheck result = service.evaluateAndReserve(
                "t1", "JOB", "job-001", "NONE", -5, 2, 0, 1, 24, "OVER", "over limit");
        assertThat(result.allowed()).isTrue();
    }

    // ── evaluateAndReserve — NONE policy ──────────────────────────────────────

    @Test
    void shouldAllowWhenNonePolicyAndWithinCap() {
        // baseCap=10, burst=0, active=5, requested=1 → 5+1=6 ≤ 10
        ResourceCheck result = service.evaluateAndReserve(
                "t1", "JOB", "job-001", "NONE", 10, 0, 5, 1, 24, "OVER", "over");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldBlockWhenNonePolicyAndOverCap() {
        // baseCap=10, burst=0, active=10, requested=1 → 11 > 10
        ResourceCheck result = service.evaluateAndReserve(
                "t1", "JOB", "job-001", "NONE", 10, 0, 10, 1, 24, "OVER_CAP", "over cap");
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void shouldAllowWhenNonePolicyWithBurstAndWithinCombinedCap() {
        // baseCap=10, burst=5, combined=15, active=12, requested=1 → 13 ≤ 15
        ResourceCheck result = service.evaluateAndReserve(
                "t1", "JOB", "job-001", "NONE", 10, 5, 12, 1, 24, "OVER", "over");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldBlockWhenNonePolicyWithBurstAndOverCombinedCap() {
        // baseCap=10, burst=5, combined=15, active=15, requested=1 → 16 > 15
        ResourceCheck result = service.evaluateAndReserve(
                "t1", "JOB", "job-001", "NONE", 10, 5, 15, 1, 24, "OVER", "over");
        assertThat(result.allowed()).isFalse();
    }

    // ── evaluateAndReserve — SLIDING_WINDOW policy ────────────────────────────

    @Test
    void shouldAllowWhenSlidingWindowPolicyAndBorrowedBelowBurst() {
        when(quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
                "t1", "JOB", "job-sw"))
                .thenReturn(null);
        when(quotaRuntimeStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // baseCap=5, burst=10, active=7, requested=1 → borrowed=7+1-5=3, burst=10 → ok
        ResourceCheck result = service.evaluateAndReserve(
                "t1", "JOB", "job-sw", "SLIDING_WINDOW", 5, 10, 7, 1, 2, "OVER", "over");

        assertThat(result.allowed()).isTrue();
        verify(quotaRuntimeStateRepository).save(any());
    }

    @Test
    void shouldBlockWhenSlidingWindowPolicyAndBorrowedExceedsBurst() {
        when(quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
                "t1", "JOB", "job-sw"))
                .thenReturn(null);
        when(quotaRuntimeStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // baseCap=5, burst=3, active=8, requested=2 → borrowed=8+2-5=5 > burst=3
        ResourceCheck result = service.evaluateAndReserve(
                "t1", "JOB", "job-sw", "SLIDING_WINDOW", 5, 3, 8, 2, 2, "OVER", "over");

        assertThat(result.allowed()).isFalse();
    }

    @Test
    void shouldAllowWhenActivePlusRequestedWithinBaseCap() {
        when(quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(anyString(), anyString(), anyString()))
                .thenReturn(null);
        when(quotaRuntimeStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // baseCap=10, burst=5, active=3, requested=2 → borrowed=0, no burst needed
        ResourceCheck result = service.evaluateAndReserve(
                "t1", "JOB", "job-sw", "SLIDING_WINDOW", 10, 5, 3, 2, 2, "OVER", "over");

        assertThat(result.allowed()).isTrue();
    }

    // ── evaluateAndReserve — CALENDAR_DAY policy ──────────────────────────────

    @Test
    void shouldAllowWhenCalendarDayPolicyAndBorrowedBelowBurst() {
        when(quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
                "t1", "JOB", "job-cal"))
                .thenReturn(null);
        when(quotaRuntimeStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // baseCap=5, burst=10, active=7, requested=1 → borrowed=3 ≤ burst=10
        ResourceCheck result = service.evaluateAndReserve(
                "t1", "JOB", "job-cal", "CALENDAR_DAY", 5, 10, 7, 1, 24, "OVER", "over");

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void shouldUpdatePeakBorrowedCountWhenHigherBorrowDetected() {
        QuotaRuntimeStateRecord existingState = new QuotaRuntimeStateRecord();
        existingState.setTenantId("t1");
        existingState.setQuotaScope("JOB");
        existingState.setOwnerCode("job-cal");
        existingState.setQuotaResetPolicy("CALENDAR_DAY");
        existingState.setPeakBorrowedCount(1);
        // Window still valid (far future)
        existingState.setWindowStartedAt(Instant.now().minusSeconds(3600));
        existingState.setWindowExpiresAt(Instant.now().plusSeconds(82800));

        when(quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
                "t1", "JOB", "job-cal"))
                .thenReturn(existingState);
        when(quotaRuntimeStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // active=8, baseCap=5, requested=1 → borrowed=4 > current peak=1
        service.evaluateAndReserve(
                "t1", "JOB", "job-cal", "CALENDAR_DAY", 5, 10, 8, 1, 24, "OVER", "over");

        verify(quotaRuntimeStateRepository, times(2)).save(any(QuotaRuntimeStateRecord.class));
    }

    // ── describe() ────────────────────────────────────────────────────────────

    @Test
    void shouldReturnDefaultSnapshotWhenTenantIdIsBlank() {
        QuotaRuntimeStateService.QuotaRuntimeSnapshot snap = service.describe(
                "", "JOB", "job-001", "CALENDAR_DAY", 5, 24);

        assertThat(snap.burstLimit()).isEqualTo(5);
        assertThat(snap.peakBorrowedCount()).isZero();
        assertThat(snap.remainingBurst()).isEqualTo(5);
    }

    @Test
    void shouldReturnDefaultSnapshotWhenBurstLimitZeroOrNone() {
        QuotaRuntimeStateService.QuotaRuntimeSnapshot snap = service.describe(
                "t1", "JOB", "job-001", "NONE", 5, 0);

        assertThat(snap.quotaResetPolicy()).isEqualTo("NONE");
        assertThat(snap.peakBorrowedCount()).isZero();
    }

    @Test
    void shouldReturnDefaultSnapshotWhenNoStateRecord() {
        when(quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
                "t1", "JOB", "job-001"))
                .thenReturn(null);

        QuotaRuntimeStateService.QuotaRuntimeSnapshot snap = service.describe(
                "t1", "JOB", "job-001", "CALENDAR_DAY", 5, 24);

        assertThat(snap.peakBorrowedCount()).isZero();
        assertThat(snap.remainingBurst()).isEqualTo(5);
    }

    // ── reconcileExpiredStates() ───────────────────────────────────────────────

    @Test
    void shouldReconcileNoExpiredStatesGracefully() {
        when(quotaRuntimeStateRepository.findExpired(any(Instant.class))).thenReturn(List.of());

        service.reconcileExpiredStates(24);

        verify(quotaRuntimeStateRepository).findExpired(any(Instant.class));
        verify(quotaRuntimeStateRepository, never()).save(any());
    }

    @Test
    void shouldResetExpiredSlidingWindowState() {
        QuotaRuntimeStateRecord expired = new QuotaRuntimeStateRecord();
        expired.setTenantId("t1");
        expired.setQuotaScope("JOB");
        expired.setOwnerCode("job-sw");
        expired.setQuotaResetPolicy("SLIDING_WINDOW");
        expired.setPeakBorrowedCount(5);
        expired.setWindowStartedAt(Instant.now().minusSeconds(7200));
        expired.setWindowExpiresAt(Instant.now().minusSeconds(3600)); // already expired

        when(quotaRuntimeStateRepository.findExpired(any(Instant.class))).thenReturn(List.of(expired));
        when(quotaRuntimeStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reconcileExpiredStates(2);

        verify(quotaRuntimeStateRepository).save(any());
    }
}
