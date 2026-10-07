package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.QuotaRuntimeStateEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.QuotaRuntimeResetScheduler;
import io.github.pinpols.batch.orchestrator.mapper.QuotaRuntimeStateMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 集成测试：QuotaRuntimeResetScheduler 使用配置的 sliding-window-hours 调用 reconcileExpiredStates， 且
 * quota-reset-enabled 标志正确地控制执行开关。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {
      "batch.quota.runtime-store=database",
      "batch.quota.backend-guard.cutover-id=quota-it-database",
      "batch.resource-scheduler.quota-reset-enabled=true",
      "batch.resource-scheduler.quota-reset-sliding-window-hours=2",
      "batch.resource-scheduler.quota-reset-scan-interval-millis=600000"
    })
@DisplayName("配额运行时状态的重置调度与预留评估集成行为,覆盖配置绑定,过期对账与突发额度判定")
class QuotaResetSchedulerIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private QuotaRuntimeResetScheduler quotaRuntimeResetScheduler;

  @Autowired
  private QuotaRuntimeStateService quotaRuntimeStateService;

  @Autowired
  private QuotaRuntimeStateMapper quotaRuntimeStateMapper;

  @Autowired
  private ResourceSchedulerProperties resourceSchedulerProperties;

  @Test
  @DisplayName("配额重置相关配置项按测试属性正确绑定,开关,滑动窗口小时数与扫描间隔均生效")
  void shouldBindQuotaResetProperties_whenConfiguredViaTestProperties() {
    assertThat(resourceSchedulerProperties.isQuotaResetEnabled()).isTrue();
    assertThat(resourceSchedulerProperties.getQuotaResetSlidingWindowHours()).isEqualTo(2);
    assertThat(resourceSchedulerProperties.getQuotaResetScanIntervalMillis()).isEqualTo(600000L);
  }

  @Test
  @DisplayName("滑动窗口已过期的配额状态在调度对账后被重置,借用峰值归零")
  void shouldResetExpiredSlidingWindowState_whenSchedulerReconciles() {
    String ownerCode = "sched-reset-" + BatchDateTimeSupport.utcEpochMillis();

    QuotaRuntimeStateEntity expired = new QuotaRuntimeStateEntity(
        null,
        "t1",
        "JOB",
        ownerCode,
        "SLIDING_WINDOW",
        BatchDateTimeSupport.utcNow().minusSeconds(10800),
        BatchDateTimeSupport.utcNow().minusSeconds(3600), // expired 1 hour ago
        7,
        null,
        BatchDateTimeSupport.utcNow(),
        BatchDateTimeSupport.utcNow(),
        null);
    quotaRuntimeStateMapper.insert(expired);

    // 直接触发调度器（调度间隔为 600 秒，测试中不会自动触发）
    quotaRuntimeResetScheduler.reconcile();

    QuotaRuntimeStateEntity updated =
        quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", ownerCode);
    assertThat(updated).isNotNull();
    assertThat(updated.peakBorrowedCount()).isZero();
  }

  @Test
  @DisplayName("不存在已过期状态时调度对账不改动数据,未过期状态的借用峰值保持不变")
  void shouldKeepUnexpiredState_whenSchedulerReconcilesWithoutExpiredWindows() {
    // 数据库中该 owner 的所有状态要么未过期要么不存在
    String ownerCode = "sched-no-expired-" + BatchDateTimeSupport.utcEpochMillis();

    // 创建一个尚未过期的状态（窗口在未来过期）
    QuotaRuntimeStateEntity active = new QuotaRuntimeStateEntity(
        null,
        "t1",
        "JOB",
        ownerCode,
        "SLIDING_WINDOW",
        BatchDateTimeSupport.utcNow().minusSeconds(1800),
        BatchDateTimeSupport.utcNow().plusSeconds(3600), // still valid
        3,
        null,
        BatchDateTimeSupport.utcNow(),
        BatchDateTimeSupport.utcNow(),
        null);
    quotaRuntimeStateMapper.insert(active);

    // reconcile() should not touch non-expired states
    quotaRuntimeResetScheduler.reconcile();

    QuotaRuntimeStateEntity afterReconcile =
        quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", ownerCode);
    assertThat(afterReconcile).isNotNull();
    // peakBorrowedCount should remain unchanged since the window hasn't expired
    assertThat(afterReconcile.peakBorrowedCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("活跃数与请求量未超出基础额度加突发额度时,预留申请被允许")
  void shouldAllowReservation_whenRequestWithinBurstCapacity() {
    String ownerCode = "sched-eval-" + BatchDateTimeSupport.utcEpochMillis();

    // base=10, burst=5, active=8, requested=1 — within cap
    var result = quotaRuntimeStateService.evaluateAndReserve(
        new QuotaRuntimeStateService.QuotaReservationRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", ownerCode),
            new QuotaRuntimeStateService.QuotaReservationPolicy(
                "SLIDING_WINDOW",
                10,
                5,
                resourceSchedulerProperties.getQuotaResetSlidingWindowHours()),
            8,
            1,
            new QuotaRuntimeStateService.QuotaReservationReason("OVER_CAP", "over")));

    assertThat(result.allowed()).isTrue();
  }

  @Test
  @DisplayName("活跃数超出基础额度导致借用数超过突发上限时,预留申请被拒绝并返回非空原因")
  void shouldBlockReservation_whenBorrowedCountExceedsBurstLimit() {
    String ownerCode = "sched-burst-" + BatchDateTimeSupport.utcEpochMillis();

    // base=5, burst=2, active=10, requested=1 → borrowed=6 > burst=2
    var result = quotaRuntimeStateService.evaluateAndReserve(
        new QuotaRuntimeStateService.QuotaReservationRequest(
            new QuotaRuntimeStateService.QuotaReservationOwner("t1", "JOB", ownerCode),
            new QuotaRuntimeStateService.QuotaReservationPolicy(
                "SLIDING_WINDOW",
                5,
                2,
                resourceSchedulerProperties.getQuotaResetSlidingWindowHours()),
            10,
            1,
            new QuotaRuntimeStateService.QuotaReservationReason("OVER_BURST", "over burst")));

    assertThat(result.allowed()).isFalse();
    assertThat(result.reasonCode()).isNotBlank();
  }
}
