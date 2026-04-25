package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.repository.QuotaRuntimeStateRepository;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 集成测试：QuotaRuntimeStateService 在真实数据库上的验证。 覆盖 SLIDING_WINDOW 和 CALENDAR_DAY 重置、峰值追踪及 reconcile。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.springframework.test.context.TestPropertySource(
    properties = "batch.quota.runtime-store=database")
class QuotaRuntimeStateIntegrationTest extends AbstractIntegrationTest {

  private static final class ReservationSpec {
    private String tenantId = "t1";
    private String quotaScope = "JOB";
    private String ownerCode;
    private String quotaResetPolicy = "NONE";
    private int baseCap;
    private int burstLimit;
    private long currentActiveCount;
    private int requestedCount = 1;
    private int slidingWindowHours = 24;
    private String reasonCode = "OVER";
    private String reasonMessage = "over";

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

  @Autowired private QuotaRuntimeStateService quotaRuntimeStateService;

  @Autowired private QuotaRuntimeStateRepository quotaRuntimeStateRepository;

  @Test
  void shouldAllowWhenWithinBaseCapNoBurstNeeded() {
    ResourceCheck result =
        quotaRuntimeStateService.evaluateAndReserve(
            new ReservationSpec()
                .ownerCode("quota-test-basic-" + System.currentTimeMillis())
                .baseCap(10)
                .burstLimit(0)
                .currentActiveCount(5)
                .reason("OVER_CAP", "over")
                .build());

    assertThat(result.allowed()).isTrue();
  }

  @Test
  void shouldBlockWhenOverBaseCapNoBurst() {
    ResourceCheck result =
        quotaRuntimeStateService.evaluateAndReserve(
            new ReservationSpec()
                .ownerCode("quota-test-block-" + System.currentTimeMillis())
                .baseCap(5)
                .burstLimit(0)
                .currentActiveCount(5)
                .reason("OVER_CAP", "over cap")
                .build());

    assertThat(result.allowed()).isFalse();
  }

  @Test
  void shouldCreateNewStateRecordForSlidingWindow() {
    String ownerCode = "sw-test-" + System.currentTimeMillis();

    ResourceCheck result =
        quotaRuntimeStateService.evaluateAndReserve(
            new ReservationSpec()
                .ownerCode(ownerCode)
                .quotaResetPolicy("SLIDING_WINDOW")
                .baseCap(5)
                .burstLimit(10)
                .currentActiveCount(7)
                .slidingWindowHours(2)
                .build());

    assertThat(result.allowed()).isTrue();

    QuotaRuntimeStateRecord state =
        quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
            "t1", "JOB", ownerCode);
    assertThat(state).isNotNull();
    assertThat(state.windowStartedAt()).isNotNull();
    assertThat(state.windowExpiresAt()).isNotNull();
    assertThat(state.windowExpiresAt()).isAfter(state.windowStartedAt());
  }

  @Test
  void shouldTrackPeakBorrowedCountForSlidingWindow() {
    String ownerCode = "sw-peak-" + System.currentTimeMillis();

    // 第一次预留：active=8, base=5, requested=2 → borrowed=5
    quotaRuntimeStateService.evaluateAndReserve(
        new ReservationSpec()
            .ownerCode(ownerCode)
            .quotaResetPolicy("SLIDING_WINDOW")
            .baseCap(5)
            .burstLimit(10)
            .currentActiveCount(8)
            .requestedCount(2)
            .slidingWindowHours(2)
            .build());

    QuotaRuntimeStateRecord state =
        quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
            "t1", "JOB", ownerCode);
    assertThat(state).isNotNull();
    assertThat(state.peakBorrowedCount()).isGreaterThan(0);
  }

  @Test
  void shouldBlockWhenBorrowedExceedsBurst() {
    String ownerCode = "sw-burst-" + System.currentTimeMillis();

    // burst=3, active=10, base=5, requested=1 → borrowed=6 > burst=3
    ResourceCheck result =
        quotaRuntimeStateService.evaluateAndReserve(
            new ReservationSpec()
                .ownerCode(ownerCode)
                .quotaResetPolicy("SLIDING_WINDOW")
                .baseCap(5)
                .burstLimit(3)
                .currentActiveCount(10)
                .slidingWindowHours(2)
                .reason("OVER_BURST", "over burst")
                .build());

    assertThat(result.allowed()).isFalse();
  }

  @Test
  void shouldCreateNewStateRecordForCalendarDay() {
    String ownerCode = "cd-test-" + System.currentTimeMillis();

    ResourceCheck result =
        quotaRuntimeStateService.evaluateAndReserve(
            new ReservationSpec()
                .ownerCode(ownerCode)
                .quotaResetPolicy("CALENDAR_DAY")
                .baseCap(5)
                .burstLimit(10)
                .currentActiveCount(7)
                .build());

    assertThat(result.allowed()).isTrue();

    QuotaRuntimeStateRecord state =
        quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
            "t1", "JOB", ownerCode);
    assertThat(state).isNotNull();
    assertThat(state.windowStartedAt()).isNotNull();
  }

  @Test
  void shouldDescribeExistingState() {
    String ownerCode = "describe-test-" + System.currentTimeMillis();

    // 先创建状态
    quotaRuntimeStateService.evaluateAndReserve(
        new QuotaRuntimeStateService.QuotaReservationRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", ownerCode),
            new QuotaRuntimeStateService.QuotaReservationPolicy("SLIDING_WINDOW", 5, 10, 7),
            2L,
            2,
            new QuotaRuntimeStateService.QuotaReservationReason("OVER", "over")));

    QuotaRuntimeStateService.QuotaRuntimeSnapshot snapshot =
        quotaRuntimeStateService.describe(
            new QuotaRuntimeStateService.QuotaDescribeRequest(
                new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", ownerCode),
                "SLIDING_WINDOW",
                10,
                2));

    assertThat(snapshot).isNotNull();
    assertThat(snapshot.quotaResetPolicy()).isEqualTo("SLIDING_WINDOW");
    assertThat(snapshot.burstLimit()).isEqualTo(10);
    assertThat(snapshot.peakBorrowedCount()).isGreaterThanOrEqualTo(0);
    assertThat(snapshot.remainingBurst()).isLessThanOrEqualTo(10);
  }

  @Test
  void shouldReconcileExpiredSlidingWindowState() {
    String ownerCode = "reconcile-sw-" + System.currentTimeMillis();

    // 直接插入一个已过期的状态
    QuotaRuntimeStateRecord expiredState =
        new QuotaRuntimeStateRecord(
            null,
            "t1",
            "JOB",
            ownerCode,
            "SLIDING_WINDOW",
            Instant.now().minusSeconds(7200),
            Instant.now().minusSeconds(3600), // expired
            5,
            null,
            Instant.now(),
            Instant.now(),
            null);
    quotaRuntimeStateRepository.save(expiredState);

    quotaRuntimeStateService.reconcileExpiredStates(2);

    // reconcile 后峰值应被重置
    QuotaRuntimeStateRecord updated =
        quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
            "t1", "JOB", ownerCode);
    assertThat(updated).isNotNull();
    assertThat(updated.peakBorrowedCount()).isZero();
  }

  @Test
  void shouldFindExpiredStatesViaRepository() {
    String ownerCode = "find-expired-" + System.currentTimeMillis();

    QuotaRuntimeStateRecord state =
        new QuotaRuntimeStateRecord(
            null,
            "t1",
            "JOB",
            ownerCode,
            "SLIDING_WINDOW",
            Instant.now().minusSeconds(3700),
            Instant.now().minusSeconds(100), // expired
            3,
            null,
            Instant.now(),
            Instant.now(),
            null);
    quotaRuntimeStateRepository.save(state);

    List<QuotaRuntimeStateRecord> expired = quotaRuntimeStateRepository.findExpired(Instant.now());

    boolean found = expired.stream().anyMatch(r -> ownerCode.equals(r.ownerCode()));
    assertThat(found).isTrue();
  }
}
