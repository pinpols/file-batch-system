package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.testing.AbstractIntegrationTest;
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
@TestPropertySource(properties = "batch.quota.runtime-store=redis")
class RedisQuotaRuntimeStateIntegrationTest extends AbstractIntegrationTest {

  @Autowired private QuotaRuntimeStateService quotaRuntimeStateService;

  // ── SLIDING_WINDOW: borrowed within burst → allow + peak 抬升

  @Test
  void slidingWindowAllowsBurstWhenWithinLimit() {
    String owner = "redis-sw-allow-" + System.currentTimeMillis();
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
  void slidingWindowBlocksWhenBorrowedExceedsBurst() {
    String owner = "redis-sw-block-" + System.currentTimeMillis();
    ResourceCheck result =
        quotaRuntimeStateService.evaluateAndReserve(reservation(owner, "SLIDING_WINDOW", 5, 3, 8));
    assertThat(result.allowed()).isFalse();
  }

  // ── peak 抬升单调（更高才覆盖，更低不回退）

  @Test
  void peakBorrowedIsMonotonicWithinWindow() {
    String owner = "redis-peak-" + System.currentTimeMillis();
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
  void calendarDayBindsWindowToCalendarBoundary() {
    String owner = "redis-cal-" + System.currentTimeMillis();
    quotaRuntimeStateService.evaluateAndReserve(reservation(owner, "CALENDAR_DAY", 5, 10, 7));
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        quotaRuntimeStateService.describe(describe(owner, "CALENDAR_DAY", 10));
    assertThat(snap.peakBorrowedCount()).isEqualTo(3);
    assertThat(snap.windowStartedAt()).isNotNull();
    assertThat(snap.windowExpiresAt()).isNotNull();
    long windowSpan = snap.windowExpiresAt().toEpochMilli() - snap.windowStartedAt().toEpochMilli();
    assertThat(windowSpan).isEqualTo(24L * 3600 * 1000);
  }

  // ── reconcile no-op：Redis 实现不依赖此调度

  @Test
  void reconcileIsNoOpForRedisBackend() {
    quotaRuntimeStateService.reconcileExpiredStates(2);
    // 没异常即过；Redis 实现的 reconcile 是 no-op
  }

  // ── describe: 未持久化 owner → 默认快照

  @Test
  void describeReturnsDefaultSnapshotForUnknownOwner() {
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        quotaRuntimeStateService.describe(
            describe("unknown-owner-" + System.currentTimeMillis(), "SLIDING_WINDOW", 5));
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
