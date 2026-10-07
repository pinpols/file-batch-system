package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 集成测试：RedisQuotaRuntimeStateService 在真实 Redis 上验证 Lua 脚本语义。 覆盖 SLIDING_WINDOW + CALENDAR_DAY 的
 * burst 占用 / peak 抬升 / 窗口过期重置 / describe 读路径。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {
      "batch.quota.runtime-store=redis",
      "batch.quota.backend-guard.cutover-id=quota-it-redis"
    })
@DisplayName("基于缓存的配额运行态:滑动窗口与自然日窗口的借用判定,峰值单调抬升,窗口边界与未知属主快照")
class RedisQuotaRuntimeStateIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private QuotaRuntimeStateService quotaRuntimeStateService;

  // ── SLIDING_WINDOW: borrowed within burst → allow + peak 抬升

  @Test
  @DisplayName("滑动窗口内未超出突发额度的借用被放行,快照记录占用峰值与剩余突发额度,并给出窗口过期时刻")
  void shouldAllowBurst_whenSlidingWindowWithinLimit() {
    String owner = "redis-sw-allow-" + BatchDateTimeSupport.utcEpochMillis();
    ResourceCheck result =
        quotaRuntimeStateService.evaluateAndReserve(reservation(owner, "SLIDING_WINDOW", 5, 10, 7));
    assertThat(result.allowed()).isTrue();

    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        quotaRuntimeStateService.describe(describe(owner, "SLIDING_WINDOW", 10));
    assertThat(snap.peakBorrowedCount()).isEqualTo(3);
    assertThat(snap.remainingBurst()).isEqualTo(7);
    assertThat(snap.windowExpiresAt()).isNotNull();
  }

  // ── SLIDING_WINDOW: borrowed > burst → block, peak 不抬升

  @Test
  @DisplayName("滑动窗口内借用超出突发额度时拒绝本次预留")
  void shouldBlock_whenSlidingWindowBorrowedExceedsBurst() {
    String owner = "redis-sw-block-" + BatchDateTimeSupport.utcEpochMillis();
    ResourceCheck result =
        quotaRuntimeStateService.evaluateAndReserve(reservation(owner, "SLIDING_WINDOW", 5, 3, 8));
    assertThat(result.allowed()).isFalse();
  }

  // ── peak 抬升单调（更高才覆盖，更低不回退）

  @Test
  @DisplayName("窗口内占用峰值只随更高占用抬升,后续更低占用不回退已记录的峰值")
  void shouldKeepPeakMonotonic_whenLowerBorrowArrives() {
    String owner = "redis-peak-" + BatchDateTimeSupport.utcEpochMillis();
    quotaRuntimeStateService.evaluateAndReserve(reservation(owner, "SLIDING_WINDOW", 5, 10, 9));
    QuotaRuntimeStateService.QuotaRuntimeSnapshot afterHigh =
        quotaRuntimeStateService.describe(describe(owner, "SLIDING_WINDOW", 10));
    assertThat(afterHigh.peakBorrowedCount()).isEqualTo(5);

    quotaRuntimeStateService.evaluateAndReserve(reservation(owner, "SLIDING_WINDOW", 5, 10, 6));
    QuotaRuntimeStateService.QuotaRuntimeSnapshot afterLow =
        quotaRuntimeStateService.describe(describe(owner, "SLIDING_WINDOW", 10));
    assertThat(afterLow.peakBorrowedCount()).isEqualTo(5);
  }

  // ── CALENDAR_DAY: peak 抬升 + 窗口绑定到自然日边界

  @Test
  @DisplayName("自然日策略下窗口绑定到当日边界,窗口跨度恰好为一天")
  void shouldBindWindowToCalendarBoundary_whenCalendarDayPolicy() {
    String owner = "redis-cal-" + BatchDateTimeSupport.utcEpochMillis();
    quotaRuntimeStateService.evaluateAndReserve(reservation(owner, "CALENDAR_DAY", 5, 10, 7));
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        quotaRuntimeStateService.describe(describe(owner, "CALENDAR_DAY", 10));
    assertThat(snap.peakBorrowedCount()).isEqualTo(3);
    assertThat(snap.windowStartedAt()).isNotNull();
    assertThat(snap.windowExpiresAt()).isNotNull();
    long windowSpan =
        snap.windowExpiresAt().toEpochMilli() - snap.windowStartedAt().toEpochMilli();
    assertThat(windowSpan).isEqualTo(24L * 3600 * 1000);
  }

  // ── reconcile no-op：Redis 实现不依赖此调度

  @Test
  @DisplayName("缓存后端不依赖调度回补,执行过期状态对账时不抛异常")
  void shouldNotThrow_whenReconcileOnCacheBackend() {
    assertThatCode(() -> quotaRuntimeStateService.reconcileExpiredStates(2))
        .doesNotThrowAnyException();
  }

  // ── describe: 未持久化 owner → 默认快照

  @Test
  @DisplayName("查询未登记的属主时返回默认快照,峰值占用为零且剩余突发额度等于配置上限")
  void shouldReturnDefaultSnapshot_whenOwnerUnknown() {
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap = quotaRuntimeStateService.describe(
        describe("unknown-owner-" + BatchDateTimeSupport.utcEpochMillis(), "SLIDING_WINDOW", 5));
    assertThat(snap.peakBorrowedCount()).isZero();
    assertThat(snap.remainingBurst()).isEqualTo(5);
  }

  private static QuotaRuntimeStateService.QuotaReservationRequest reservation(
      String owner, String policy, int baseCap, int burst, long active) {
    return new QuotaRuntimeStateService.QuotaReservationRequest(
        new QuotaRuntimeStateService.QuotaReservationOwner("redis-it-tenant", "JOB", owner),
        new QuotaRuntimeStateService.QuotaReservationPolicy(policy, baseCap, burst, 2),
        active,
        1,
        new QuotaRuntimeStateService.QuotaReservationReason("OVER", "over"));
  }

  private static QuotaRuntimeStateService.QuotaDescribeRequest describe(
      String owner, String policy, int burst) {
    return new QuotaRuntimeStateService.QuotaDescribeRequest(
        new QuotaRuntimeStateService.QuotaReservationOwner("redis-it-tenant", "JOB", owner),
        policy,
        burst,
        2);
  }
}
