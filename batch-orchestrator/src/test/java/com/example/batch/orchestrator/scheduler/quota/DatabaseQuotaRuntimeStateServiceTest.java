package com.example.batch.orchestrator.scheduler.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateEntity;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.infrastructure.quota.DatabaseQuotaRuntimeStateService;
import com.example.batch.orchestrator.mapper.QuotaRuntimeStateMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseQuotaRuntimeStateServiceTest {

  private static final class ReservationSpec {
    private String tenantId = "t1";
    private String quotaScope = "JOB";
    private String ownerCode = "job-001";
    private String quotaResetPolicy = "NONE";
    private int baseCap;
    private int burstLimit;
    private long currentActiveCount;
    private int requestedCount = 1;
    private int slidingWindowHours = 24;
    private String reasonCode = "OVER";
    private String reasonMessage = "over";

    private ReservationSpec tenantId(String tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    private ReservationSpec ownerCode(String ownerCode) {
      this.ownerCode = ownerCode;
      return this;
    }

    private ReservationSpec quotaResetPolicy(String quotaResetPolicy) {
      this.quotaResetPolicy = quotaResetPolicy;
      return this;
    }

    private ReservationSpec baseCap(int baseCap) {
      this.baseCap = baseCap;
      return this;
    }

    private ReservationSpec burstLimit(int burstLimit) {
      this.burstLimit = burstLimit;
      return this;
    }

    private ReservationSpec currentActiveCount(long currentActiveCount) {
      this.currentActiveCount = currentActiveCount;
      return this;
    }

    private ReservationSpec requestedCount(int requestedCount) {
      this.requestedCount = requestedCount;
      return this;
    }

    private ReservationSpec slidingWindowHours(int slidingWindowHours) {
      this.slidingWindowHours = slidingWindowHours;
      return this;
    }

    private ReservationSpec reason(String reasonCode, String reasonMessage) {
      this.reasonCode = reasonCode;
      this.reasonMessage = reasonMessage;
      return this;
    }

    private QuotaRuntimeStateService.QuotaReservationRequest build() {
      return new QuotaRuntimeStateService.QuotaReservationRequest(
          new QuotaRuntimeStateService.QuotaReservationOwner(tenantId, quotaScope, ownerCode),
          new QuotaRuntimeStateService.QuotaReservationPolicy(
              quotaResetPolicy, baseCap, burstLimit, slidingWindowHours),
          currentActiveCount,
          requestedCount,
          new QuotaRuntimeStateService.QuotaReservationReason(reasonCode, reasonMessage));
    }
  }

  private QuotaRuntimeStateMapper quotaRuntimeStateMapper;
  private DatabaseQuotaRuntimeStateService service;

  @BeforeEach
  void setUp() {
    quotaRuntimeStateMapper = mock(QuotaRuntimeStateMapper.class);
    // C-2.8：selfProvider 在单测里直通（不走 REQUIRES_NEW 子事务），
    // 等 reconcileOne 的事务语义由集成测试覆盖
    org.springframework.beans.factory.ObjectProvider<DatabaseQuotaRuntimeStateService>
        selfProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
    service =
        new DatabaseQuotaRuntimeStateService(
            quotaRuntimeStateMapper,
            new BatchTimezoneProvider(new BatchTimezoneProperties()),
            selfProvider);
    org.mockito.Mockito.when(selfProvider.getObject()).thenReturn(service);
  }

  // ── evaluateAndReserve — guard conditions ─────────────────────────────────

  @Test
  void shouldAllowWhenTenantIdIsBlank() {
    ResourceCheck result =
        service.evaluateAndReserve(
            new ReservationSpec()
                .tenantId("")
                .baseCap(10)
                .burstLimit(2)
                .currentActiveCount(0)
                .reason("OVER", "over limit")
                .build());
    assertThat(result.allowed()).isTrue();
  }

  @Test
  void shouldAllowWhenBaseCapIsZero() {
    ResourceCheck result =
        service.evaluateAndReserve(
            new ReservationSpec()
                .baseCap(0)
                .burstLimit(2)
                .currentActiveCount(0)
                .reason("OVER", "over limit")
                .build());
    assertThat(result.allowed()).isTrue();
  }

  @Test
  void shouldAllowWhenBaseCapIsNegative() {
    ResourceCheck result =
        service.evaluateAndReserve(
            new ReservationSpec()
                .baseCap(-5)
                .burstLimit(2)
                .currentActiveCount(0)
                .reason("OVER", "over limit")
                .build());
    assertThat(result.allowed()).isTrue();
  }

  // ── evaluateAndReserve — NONE policy ──────────────────────────────────────

  @Test
  void shouldAllowWhenNonePolicyAndWithinCap() {
    // baseCap=10, burst=0, active=5, requested=1 → 5+1=6 ≤ 10
    ResourceCheck result =
        service.evaluateAndReserve(
            new ReservationSpec().baseCap(10).burstLimit(0).currentActiveCount(5).build());
    assertThat(result.allowed()).isTrue();
  }

  @Test
  void shouldBlockWhenNonePolicyAndOverCap() {
    // baseCap=10, burst=0, active=10, requested=1 → 11 > 10
    ResourceCheck result =
        service.evaluateAndReserve(
            new ReservationSpec()
                .baseCap(10)
                .burstLimit(0)
                .currentActiveCount(10)
                .reason("OVER_CAP", "over cap")
                .build());
    assertThat(result.allowed()).isFalse();
  }

  @Test
  void shouldAllowWhenNonePolicyWithBurstAndWithinCombinedCap() {
    // baseCap=10, burst=5, combined=15, active=12, requested=1 → 13 ≤ 15
    ResourceCheck result =
        service.evaluateAndReserve(
            new ReservationSpec().baseCap(10).burstLimit(5).currentActiveCount(12).build());
    assertThat(result.allowed()).isTrue();
  }

  @Test
  void shouldBlockWhenNonePolicyWithBurstAndOverCombinedCap() {
    // baseCap=10, burst=5, combined=15, active=15, requested=1 → 16 > 15
    ResourceCheck result =
        service.evaluateAndReserve(
            new ReservationSpec().baseCap(10).burstLimit(5).currentActiveCount(15).build());
    assertThat(result.allowed()).isFalse();
  }

  // ── evaluateAndReserve — SLIDING_WINDOW policy ────────────────────────────

  @Test
  void shouldAllowWhenSlidingWindowPolicyAndBorrowedBelowBurst() {
    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", "job-sw"))
        .thenReturn(null);
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    // baseCap=5, burst=10, active=7, requested=1 → borrowed=7+1-5=3, burst=10 → ok
    ResourceCheck result =
        service.evaluateAndReserve(
            new ReservationSpec()
                .ownerCode("job-sw")
                .quotaResetPolicy("SLIDING_WINDOW")
                .baseCap(5)
                .burstLimit(10)
                .currentActiveCount(7)
                .slidingWindowHours(2)
                .build());

    assertThat(result.allowed()).isTrue();
    verify(quotaRuntimeStateMapper).insert(any());
  }

  @Test
  void shouldBlockWhenSlidingWindowPolicyAndBorrowedExceedsBurst() {
    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", "job-sw"))
        .thenReturn(null);
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    // baseCap=5, burst=3, active=8, requested=2 → borrowed=8+2-5=5 > burst=3
    ResourceCheck result =
        service.evaluateAndReserve(
            new ReservationSpec()
                .ownerCode("job-sw")
                .quotaResetPolicy("SLIDING_WINDOW")
                .baseCap(5)
                .burstLimit(3)
                .currentActiveCount(8)
                .requestedCount(2)
                .slidingWindowHours(2)
                .build());

    assertThat(result.allowed()).isFalse();
  }

  @Test
  void shouldAllowWhenActivePlusRequestedWithinBaseCap() {
    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner(
            anyString(), anyString(), anyString()))
        .thenReturn(null);
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    // baseCap=10, burst=5, active=3, requested=2 → borrowed=0, no burst needed
    ResourceCheck result =
        service.evaluateAndReserve(
            new ReservationSpec()
                .ownerCode("job-sw")
                .quotaResetPolicy("SLIDING_WINDOW")
                .baseCap(10)
                .burstLimit(5)
                .currentActiveCount(3)
                .requestedCount(2)
                .slidingWindowHours(2)
                .build());

    assertThat(result.allowed()).isTrue();
  }

  // ── evaluateAndReserve — CALENDAR_DAY policy ──────────────────────────────

  @Test
  void shouldAllowWhenCalendarDayPolicyAndBorrowedBelowBurst() {
    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", "job-cal"))
        .thenReturn(null);
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    // baseCap=5, burst=10, active=7, requested=1 → borrowed=3 ≤ burst=10
    ResourceCheck result =
        service.evaluateAndReserve(
            new ReservationSpec()
                .ownerCode("job-cal")
                .quotaResetPolicy("CALENDAR_DAY")
                .baseCap(5)
                .burstLimit(10)
                .currentActiveCount(7)
                .build());

    assertThat(result.allowed()).isTrue();
  }

  @Test
  void shouldUpdatePeakBorrowedCountWhenHigherBorrowDetected() {
    QuotaRuntimeStateEntity existingState =
        new QuotaRuntimeStateEntity(
            null,
            "t1",
            "JOB",
            "job-cal",
            "CALENDAR_DAY",
            Instant.now().minusSeconds(3600),
            Instant.now().plusSeconds(82800),
            1,
            null,
            Instant.now(),
            Instant.now(),
            null);
    // 窗口仍然有效（远未到期）

    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", "job-cal"))
        .thenReturn(existingState);
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    // active=8, baseCap=5, requested=1 → borrowed=4 > current peak=1
    service.evaluateAndReserve(
        new ReservationSpec()
            .ownerCode("job-cal")
            .quotaResetPolicy("CALENDAR_DAY")
            .baseCap(5)
            .burstLimit(10)
            .currentActiveCount(8)
            .build());

    verify(quotaRuntimeStateMapper, times(2)).insert(any(QuotaRuntimeStateEntity.class));
  }

  // ── describe() ────────────────────────────────────────────────────────────

  @Test
  void shouldReturnDefaultSnapshotWhenTenantIdIsBlank() {
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(
            new QuotaRuntimeStateService.QuotaDescribeRequest(
                new QuotaRuntimeStateService.QuotaReservationOwner("", "JOB", "job-001"),
                "CALENDAR_DAY",
                5,
                24));

    assertThat(snap.burstLimit()).isEqualTo(5);
    assertThat(snap.peakBorrowedCount()).isZero();
    assertThat(snap.remainingBurst()).isEqualTo(5);
  }

  @Test
  void shouldReturnDefaultSnapshotWhenBurstLimitZeroOrNone() {
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(
            new QuotaRuntimeStateService.QuotaDescribeRequest(
                new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "job-001"),
                "NONE",
                5,
                0));

    assertThat(snap.quotaResetPolicy()).isEqualTo("NONE");
    assertThat(snap.peakBorrowedCount()).isZero();
  }

  @Test
  void shouldReturnDefaultSnapshotWhenNoStateRecord() {
    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", "job-001"))
        .thenReturn(null);

    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(
            new QuotaRuntimeStateService.QuotaDescribeRequest(
                new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "job-001"),
                "CALENDAR_DAY",
                5,
                24));

    assertThat(snap.peakBorrowedCount()).isZero();
    assertThat(snap.remainingBurst()).isEqualTo(5);
  }

  // ── reconcileExpiredStates() ───────────────────────────────────────────────

  @Test
  void shouldReconcileNoExpiredStatesGracefully() {
    when(quotaRuntimeStateMapper.selectExpired(any(Instant.class))).thenReturn(List.of());

    service.reconcileExpiredStates(24);

    verify(quotaRuntimeStateMapper).selectExpired(any(Instant.class));
    verify(quotaRuntimeStateMapper, never()).insert(any());
    verify(quotaRuntimeStateMapper, never()).updateWithCas(any());
  }

  @Test
  void shouldResetExpiredSlidingWindowState() {
    QuotaRuntimeStateEntity expired =
        new QuotaRuntimeStateEntity(
            null,
            "t1",
            "JOB",
            "job-sw",
            "SLIDING_WINDOW",
            Instant.now().minusSeconds(7200),
            Instant.now().minusSeconds(3600),
            5,
            null,
            Instant.now(),
            Instant.now(),
            null); // already expired

    when(quotaRuntimeStateMapper.selectExpired(any(Instant.class))).thenReturn(List.of(expired));
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    service.reconcileExpiredStates(2);

    verify(quotaRuntimeStateMapper).insert(any());
  }
}
