package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.domain.entity.QuotaRuntimeStateEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.mapper.QuotaRuntimeStateMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
    properties = {
      "batch.quota.runtime-store=database",
      "batch.quota.backend-guard.cutover-id=quota-it-database"
    })
@DisplayName("配额运行态服务在真实数据库上的集成验证,覆盖滑动窗口与自然日重置,峰值追踪,快照查询,过期对账与预留更新")
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

  @Autowired
  private QuotaRuntimeStateService quotaRuntimeStateService;

  @Autowired
  private QuotaRuntimeStateMapper quotaRuntimeStateMapper;

  @Test
  @DisplayName("当前活跃数低于基础额度且无需借用突发额度时,预留请求应被放行")
  void shouldAllowWhenWithinBaseCapNoBurstNeeded() {
    ResourceCheck result = quotaRuntimeStateService.evaluateAndReserve(new ReservationSpec()
        .ownerCode("quota-test-basic-" + BatchDateTimeSupport.utcEpochMillis())
        .baseCap(10)
        .burstLimit(0)
        .currentActiveCount(5)
        .reason("OVER_CAP", "over")
        .build());

    assertThat(result.allowed()).isTrue();
  }

  @Test
  @DisplayName("当前活跃数已达基础额度且未配置突发额度时,预留请求应被拒绝")
  void shouldBlockWhenOverBaseCapNoBurst() {
    ResourceCheck result = quotaRuntimeStateService.evaluateAndReserve(new ReservationSpec()
        .ownerCode("quota-test-block-" + BatchDateTimeSupport.utcEpochMillis())
        .baseCap(5)
        .burstLimit(0)
        .currentActiveCount(5)
        .reason("OVER_CAP", "over cap")
        .build());

    assertThat(result.allowed()).isFalse();
  }

  @Test
  @DisplayName("首次按滑动窗口预留时应落库状态记录,窗口起止时间均非空且结束时间晚于开始时间")
  void shouldCreateNewStateRecordForSlidingWindow() {
    String ownerCode = "sw-test-" + BatchDateTimeSupport.utcEpochMillis();

    ResourceCheck result = quotaRuntimeStateService.evaluateAndReserve(new ReservationSpec()
        .ownerCode(ownerCode)
        .quotaResetPolicy("SLIDING_WINDOW")
        .baseCap(5)
        .burstLimit(10)
        .currentActiveCount(7)
        .slidingWindowHours(2)
        .build());

    assertThat(result.allowed()).isTrue();

    QuotaRuntimeStateEntity state =
        quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", ownerCode);
    assertThat(state).isNotNull();
    assertThat(state.windowStartedAt()).isNotNull();
    assertThat(state.windowExpiresAt()).isNotNull();
    assertThat(state.windowExpiresAt()).isAfter(state.windowStartedAt());
  }

  @Test
  @DisplayName("滑动窗口下发生额度借用时应记录大于零的峰值借用数")
  void shouldTrackPeakBorrowedCountForSlidingWindow() {
    String ownerCode = "sw-peak-" + BatchDateTimeSupport.utcEpochMillis();

    // 第一次预留：active=8, base=5, requested=2 → borrowed=5
    quotaRuntimeStateService.evaluateAndReserve(new ReservationSpec()
        .ownerCode(ownerCode)
        .quotaResetPolicy("SLIDING_WINDOW")
        .baseCap(5)
        .burstLimit(10)
        .currentActiveCount(8)
        .requestedCount(2)
        .slidingWindowHours(2)
        .build());

    QuotaRuntimeStateEntity state =
        quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", ownerCode);
    assertThat(state).isNotNull();
    assertThat(state.peakBorrowedCount()).isGreaterThan(0);
  }

  @Test
  @DisplayName("借用数超过突发上限时,预留请求应被拒绝")
  void shouldBlockWhenBorrowedExceedsBurst() {
    String ownerCode = "sw-burst-" + BatchDateTimeSupport.utcEpochMillis();

    // burst=3, active=10, base=5, requested=1 → borrowed=6 > burst=3
    ResourceCheck result = quotaRuntimeStateService.evaluateAndReserve(new ReservationSpec()
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
  @DisplayName("首次按自然日重置预留时应落库状态记录并写入窗口开始时间")
  void shouldCreateNewStateRecordForCalendarDay() {
    String ownerCode = "cd-test-" + BatchDateTimeSupport.utcEpochMillis();

    ResourceCheck result = quotaRuntimeStateService.evaluateAndReserve(new ReservationSpec()
        .ownerCode(ownerCode)
        .quotaResetPolicy("CALENDAR_DAY")
        .baseCap(5)
        .burstLimit(10)
        .currentActiveCount(7)
        .build());

    assertThat(result.allowed()).isTrue();

    QuotaRuntimeStateEntity state =
        quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", ownerCode);
    assertThat(state).isNotNull();
    assertThat(state.windowStartedAt()).isNotNull();
  }

  @Test
  @DisplayName("状态已存在时查询快照应回填重置策略,突发上限,峰值借用数与剩余突发额度")
  void shouldDescribeExistingState() {
    String ownerCode = "describe-test-" + BatchDateTimeSupport.utcEpochMillis();

    // 先创建状态
    quotaRuntimeStateService.evaluateAndReserve(
        new QuotaRuntimeStateService.QuotaReservationRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", ownerCode),
            new QuotaRuntimeStateService.QuotaReservationPolicy("SLIDING_WINDOW", 5, 10, 7),
            2L,
            2,
            new QuotaRuntimeStateService.QuotaReservationReason("OVER", "over")));

    QuotaRuntimeStateService.QuotaRuntimeSnapshot snapshot =
        quotaRuntimeStateService.describe(new QuotaRuntimeStateService.QuotaDescribeRequest(
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
  @DisplayName("滑动窗口已过期时对账应将该状态的峰值借用数重置为零")
  void shouldReconcileExpiredSlidingWindowState() {
    String ownerCode = "reconcile-sw-" + BatchDateTimeSupport.utcEpochMillis();

    // 直接插入一个已过期的状态
    QuotaRuntimeStateEntity expiredState = new QuotaRuntimeStateEntity(
        null,
        "t1",
        "JOB",
        ownerCode,
        "SLIDING_WINDOW",
        BatchDateTimeSupport.utcNow().minusSeconds(7200),
        BatchDateTimeSupport.utcNow().minusSeconds(3600), // expired
        5,
        null,
        BatchDateTimeSupport.utcNow(),
        BatchDateTimeSupport.utcNow(),
        null);
    quotaRuntimeStateMapper.insert(expiredState);

    quotaRuntimeStateService.reconcileExpiredStates(2);

    // reconcile 后峰值应被重置
    QuotaRuntimeStateEntity updated =
        quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", ownerCode);
    assertThat(updated).isNotNull();
    assertThat(updated.peakBorrowedCount()).isZero();
  }

  @Test
  @DisplayName("窗口已过期时再次预留应在一次版本更新内刷新窗口,并同时记录借用峰值与新的过期时间")
  void shouldRefreshExpiredWindowAndReserveWithOneVersionUpdate() {
    String ownerCode = "sw-reset-reserve-" + BatchDateTimeSupport.utcEpochMillis();
    var now = BatchDateTimeSupport.utcNow();
    quotaRuntimeStateMapper.insert(new QuotaRuntimeStateEntity(
        null,
        "t1",
        "JOB",
        ownerCode,
        "SLIDING_WINDOW",
        now.minusSeconds(7200),
        now.minusSeconds(3600),
        9,
        null,
        now,
        now,
        null));
    QuotaRuntimeStateEntity before =
        quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", ownerCode);

    ResourceCheck result = quotaRuntimeStateService.evaluateAndReserve(new ReservationSpec()
        .ownerCode(ownerCode)
        .quotaResetPolicy("SLIDING_WINDOW")
        .baseCap(5)
        .burstLimit(10)
        .currentActiveCount(7)
        .slidingWindowHours(2)
        .build());

    QuotaRuntimeStateEntity after =
        quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", ownerCode);
    assertThat(result.allowed()).isTrue();
    assertThat(after.version()).isEqualTo(before.version() + 1);
    assertThat(after.peakBorrowedCount()).isEqualTo(3);
    assertThat(after.windowExpiresAt()).isAfter(now);
  }

  @Test
  @DisplayName("已过期的状态应能被过期状态查询检索到")
  void shouldFindExpiredStatesViaRepository() {
    String ownerCode = "find-expired-" + BatchDateTimeSupport.utcEpochMillis();

    QuotaRuntimeStateEntity state = new QuotaRuntimeStateEntity(
        null,
        "t1",
        "JOB",
        ownerCode,
        "SLIDING_WINDOW",
        BatchDateTimeSupport.utcNow().minusSeconds(3700),
        BatchDateTimeSupport.utcNow().minusSeconds(100), // expired
        3,
        null,
        BatchDateTimeSupport.utcNow(),
        BatchDateTimeSupport.utcNow(),
        null);
    quotaRuntimeStateMapper.insert(state);

    List<QuotaRuntimeStateEntity> expired =
        quotaRuntimeStateMapper.selectExpired(BatchDateTimeSupport.utcNow());

    boolean found = expired.stream().anyMatch(r -> ownerCode.equals(r.ownerCode()));
    assertThat(found).isTrue();
  }
}
