package io.github.pinpols.batch.orchestrator.scheduler.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.domain.entity.QuotaRuntimeStateEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.infrastructure.quota.DatabaseQuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.mapper.QuotaRuntimeStateMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@DisplayName("数据库配额运行态服务的准入判定,快照查询与过期状态回收")
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
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    service = new DatabaseQuotaRuntimeStateService(
        quotaRuntimeStateMapper,
        new BatchTimezoneProvider(new BatchTimezoneProperties()),
        transactionManager);
  }

  // ── evaluateAndReserve — guard conditions ─────────────────────────────────

  @Test
  @DisplayName("租户标识为空时不参与配额判定直接放行")
  void shouldAllowWhenTenantIdIsBlank() {
    ResourceCheck result = service.evaluateAndReserve(new ReservationSpec()
        .tenantId("")
        .baseCap(10)
        .burstLimit(2)
        .currentActiveCount(0)
        .reason("OVER", "over limit")
        .build());
    assertThat(result.allowed()).isTrue();
  }

  @Test
  @DisplayName("基础配额上限为零时直接放行")
  void shouldAllowWhenBaseCapIsZero() {
    ResourceCheck result = service.evaluateAndReserve(new ReservationSpec()
        .baseCap(0)
        .burstLimit(2)
        .currentActiveCount(0)
        .reason("OVER", "over limit")
        .build());
    assertThat(result.allowed()).isTrue();
  }

  @Test
  @DisplayName("基础配额上限为负数时直接放行")
  void shouldAllowWhenBaseCapIsNegative() {
    ResourceCheck result = service.evaluateAndReserve(new ReservationSpec()
        .baseCap(-5)
        .burstLimit(2)
        .currentActiveCount(0)
        .reason("OVER", "over limit")
        .build());
    assertThat(result.allowed()).isTrue();
  }

  // ── evaluateAndReserve — NONE policy ──────────────────────────────────────

  @Test
  @DisplayName("不重置策略下已用与申请量之和未超基础上限时放行")
  void shouldAllowWhenNonePolicyAndWithinCap() {
    // baseCap=10, burst=0, active=5, requested=1 → 5+1=6 ≤ 10
    ResourceCheck result = service.evaluateAndReserve(
        new ReservationSpec().baseCap(10).burstLimit(0).currentActiveCount(5).build());
    assertThat(result.allowed()).isTrue();
  }

  @Test
  @DisplayName("不重置策略下已用与申请量之和超过基础上限时拒绝")
  void shouldBlockWhenNonePolicyAndOverCap() {
    // baseCap=10, burst=0, active=10, requested=1 → 11 > 10
    ResourceCheck result = service.evaluateAndReserve(new ReservationSpec()
        .baseCap(10)
        .burstLimit(0)
        .currentActiveCount(10)
        .reason("OVER_CAP", "over cap")
        .build());
    assertThat(result.allowed()).isFalse();
  }

  @Test
  @DisplayName("不重置策略下叠加突发额度后总量未超合并上限时放行")
  void shouldAllowWhenNonePolicyWithBurstAndWithinCombinedCap() {
    // baseCap=10, burst=5, combined=15, active=12, requested=1 → 13 ≤ 15
    ResourceCheck result = service.evaluateAndReserve(
        new ReservationSpec().baseCap(10).burstLimit(5).currentActiveCount(12).build());
    assertThat(result.allowed()).isTrue();
  }

  @Test
  @DisplayName("不重置策略下叠加突发额度后总量超过合并上限时拒绝")
  void shouldBlockWhenNonePolicyWithBurstAndOverCombinedCap() {
    // baseCap=10, burst=5, combined=15, active=15, requested=1 → 16 > 15
    ResourceCheck result = service.evaluateAndReserve(
        new ReservationSpec().baseCap(10).burstLimit(5).currentActiveCount(15).build());
    assertThat(result.allowed()).isFalse();
  }

  // ── evaluateAndReserve — SLIDING_WINDOW policy ────────────────────────────

  @Test
  @DisplayName("滑动窗口策略下借用额度未超突发上限时放行并写入状态记录")
  void shouldAllowWhenSlidingWindowPolicyAndBorrowedBelowBurst() {
    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", "job-sw"))
        .thenReturn(null);
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    // baseCap=5, burst=10, active=7, requested=1 → borrowed=7+1-5=3, burst=10 → ok
    ResourceCheck result = service.evaluateAndReserve(new ReservationSpec()
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
  @DisplayName("滑动窗口策略下借用额度超过突发上限时拒绝")
  void shouldBlockWhenSlidingWindowPolicyAndBorrowedExceedsBurst() {
    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", "job-sw"))
        .thenReturn(null);
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    // baseCap=5, burst=3, active=8, requested=2 → borrowed=8+2-5=5 > burst=3
    ResourceCheck result = service.evaluateAndReserve(new ReservationSpec()
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
  @DisplayName("滑动窗口策略下已用与申请量之和未超基础上限时放行")
  void shouldAllowWhenActivePlusRequestedWithinBaseCap() {
    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner(
            anyString(), anyString(), anyString()))
        .thenReturn(null);
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    // baseCap=10, burst=5, active=3, requested=2 → borrowed=0, no burst needed
    ResourceCheck result = service.evaluateAndReserve(new ReservationSpec()
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
  @DisplayName("自然日重置策略下借用额度未超突发上限时放行")
  void shouldAllowWhenCalendarDayPolicyAndBorrowedBelowBurst() {
    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", "job-cal"))
        .thenReturn(null);
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    // baseCap=5, burst=10, active=7, requested=1 → borrowed=3 ≤ burst=10
    ResourceCheck result = service.evaluateAndReserve(new ReservationSpec()
        .ownerCode("job-cal")
        .quotaResetPolicy("CALENDAR_DAY")
        .baseCap(5)
        .burstLimit(10)
        .currentActiveCount(7)
        .build());

    assertThat(result.allowed()).isTrue();
  }

  @Test
  @DisplayName("本次借用高于已有峰值时写入更新后的峰值状态记录")
  void shouldUpdatePeakBorrowedCountWhenHigherBorrowDetected() {
    QuotaRuntimeStateEntity existingState = new QuotaRuntimeStateEntity(
        null,
        "t1",
        "JOB",
        "job-cal",
        "CALENDAR_DAY",
        BatchDateTimeSupport.utcNow().minusSeconds(3600),
        BatchDateTimeSupport.utcNow().plusSeconds(82800),
        1,
        null,
        BatchDateTimeSupport.utcNow(),
        BatchDateTimeSupport.utcNow(),
        null);
    // 窗口仍然有效（远未到期）

    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", "job-cal"))
        .thenReturn(existingState);
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    // active=8, baseCap=5, requested=1 → borrowed=4 > current peak=1
    service.evaluateAndReserve(new ReservationSpec()
        .ownerCode("job-cal")
        .quotaResetPolicy("CALENDAR_DAY")
        .baseCap(5)
        .burstLimit(10)
        .currentActiveCount(8)
        .build());

    verify(quotaRuntimeStateMapper).insert(any(QuotaRuntimeStateEntity.class));
  }

  @Test
  @DisplayName("已过期的持久化窗口在单次写入中完成刷新与额度预留,峰值与版本号同步更新")
  void shouldRefreshExpiredWindowAndReserve_whenPersistedWindowHasExpired() {
    Instant old = Instant.now().minusSeconds(172800);
    var state = new QuotaRuntimeStateEntity(
        1L,
        "t1",
        "JOB",
        "job-sw",
        "SLIDING_WINDOW",
        old,
        old.plusSeconds(3600),
        2,
        old,
        old,
        old,
        7L);
    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", "job-sw"))
        .thenReturn(state);
    var databaseVersion = new AtomicLong(7);
    when(quotaRuntimeStateMapper.updateWithCas(any())).thenAnswer(invocation -> {
      QuotaRuntimeStateEntity update = invocation.getArgument(0);
      assertThat(update.peakBorrowedCount()).isEqualTo(3);
      assertThat(update.windowExpiresAt()).isAfter(Instant.now());
      return databaseVersion.compareAndSet(update.version(), update.version() + 1) ? 1 : 0;
    });

    var result = service.evaluateAndReserve(new ReservationSpec()
        .ownerCode("job-sw")
        .quotaResetPolicy("SLIDING_WINDOW")
        .baseCap(5)
        .burstLimit(10)
        .currentActiveCount(7)
        .slidingWindowHours(1)
        .build());

    assertThat(result.allowed()).isTrue();
    verify(quotaRuntimeStateMapper).updateWithCas(any());
    assertThat(databaseVersion.get()).isEqualTo(8);
  }

  // ── describe() ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("查询快照时租户标识为空返回默认值,峰值为零且剩余突发等于突发上限")
  void shouldReturnDefaultSnapshotWhenTenantIdIsBlank() {
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(new QuotaRuntimeStateService.QuotaDescribeRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("", "JOB", "job-001"),
            "CALENDAR_DAY",
            5,
            24));

    assertThat(snap.burstLimit()).isEqualTo(5);
    assertThat(snap.peakBorrowedCount()).isZero();
    assertThat(snap.remainingBurst()).isEqualTo(5);
  }

  @Test
  @DisplayName("突发上限为零或策略为不重置时返回默认快照,峰值归零")
  void shouldReturnDefaultSnapshotWhenBurstLimitZeroOrNone() {
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(new QuotaRuntimeStateService.QuotaDescribeRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "job-001"),
            "NONE",
            5,
            0));

    assertThat(snap.quotaResetPolicy()).isEqualTo("NONE");
    assertThat(snap.peakBorrowedCount()).isZero();
  }

  @Test
  @DisplayName("没有状态记录时返回默认快照,峰值为零且剩余突发等于突发上限")
  void shouldReturnDefaultSnapshotWhenNoStateRecord() {
    when(quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", "job-001"))
        .thenReturn(null);

    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        service.describe(new QuotaRuntimeStateService.QuotaDescribeRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", "job-001"),
            "CALENDAR_DAY",
            5,
            24));

    assertThat(snap.peakBorrowedCount()).isZero();
    assertThat(snap.remainingBurst()).isEqualTo(5);
  }

  // ── reconcileExpiredStates() ───────────────────────────────────────────────

  @Test
  @DisplayName("没有过期状态时只做过期查询,不插入也不更新任何状态")
  void shouldReconcileNoExpiredStatesGracefully() {
    when(quotaRuntimeStateMapper.selectExpired(any(Instant.class))).thenReturn(List.of());

    service.reconcileExpiredStates(24);

    verify(quotaRuntimeStateMapper).selectExpired(any(Instant.class));
    verify(quotaRuntimeStateMapper, never()).insert(any());
    verify(quotaRuntimeStateMapper, never()).updateWithCas(any());
  }

  @Test
  @DisplayName("已过期的滑动窗口状态被重置并重新写入")
  void shouldResetExpiredSlidingWindowState() {
    QuotaRuntimeStateEntity expired = new QuotaRuntimeStateEntity(
        null,
        "t1",
        "JOB",
        "job-sw",
        "SLIDING_WINDOW",
        BatchDateTimeSupport.utcNow().minusSeconds(7200),
        BatchDateTimeSupport.utcNow().minusSeconds(3600),
        5,
        null,
        BatchDateTimeSupport.utcNow(),
        BatchDateTimeSupport.utcNow(),
        null); // already expired

    when(quotaRuntimeStateMapper.selectExpired(any(Instant.class))).thenReturn(List.of(expired));
    when(quotaRuntimeStateMapper.insert(any())).thenReturn(1);

    service.reconcileExpiredStates(2);

    verify(quotaRuntimeStateMapper).insert(any());
  }
}
