package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateRecord;
import com.example.batch.orchestrator.infrastructure.scheduler.QuotaRuntimeResetScheduler;
import com.example.batch.orchestrator.mapper.QuotaRuntimeStateMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
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
      "batch.resource-scheduler.quota-reset-enabled=true",
      "batch.resource-scheduler.quota-reset-sliding-window-hours=2",
      "batch.resource-scheduler.quota-reset-scan-interval-millis=600000"
    })
class QuotaResetSchedulerIntegrationTest extends AbstractIntegrationTest {

  @Autowired private QuotaRuntimeResetScheduler quotaRuntimeResetScheduler;

  @Autowired private QuotaRuntimeStateService quotaRuntimeStateService;

  @Autowired private QuotaRuntimeStateMapper quotaRuntimeStateMapper;

  @Autowired private ResourceSchedulerProperties resourceSchedulerProperties;

  @Test
  void resourceSchedulerPropertiesAreLoadedCorrectly() {
    assertThat(resourceSchedulerProperties.isQuotaResetEnabled()).isTrue();
    assertThat(resourceSchedulerProperties.getQuotaResetSlidingWindowHours()).isEqualTo(2);
    assertThat(resourceSchedulerProperties.getQuotaResetScanIntervalMillis()).isEqualTo(600000L);
  }

  @Test
  void schedulerReconcileResetsExpiredSlidingWindowState() {
    String ownerCode = "sched-reset-" + System.currentTimeMillis();

    QuotaRuntimeStateRecord expired =
        new QuotaRuntimeStateRecord(
            null,
            "t1",
            "JOB",
            ownerCode,
            "SLIDING_WINDOW",
            Instant.now().minusSeconds(10800),
            Instant.now().minusSeconds(3600), // expired 1 hour ago
            7,
            null,
            Instant.now(),
            Instant.now(),
            null);
    quotaRuntimeStateMapper.insert(expired);

    // 直接触发调度器（调度间隔为 600 秒，测试中不会自动触发）
    quotaRuntimeResetScheduler.reconcile();

    QuotaRuntimeStateRecord updated =
        quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", ownerCode);
    assertThat(updated).isNotNull();
    assertThat(updated.peakBorrowedCount()).isZero();
  }

  @Test
  void schedulerReconcileHandlesNoExpiredStatesGracefully() {
    // 数据库中该 owner 的所有状态要么未过期要么不存在
    String ownerCode = "sched-no-expired-" + System.currentTimeMillis();

    // 创建一个尚未过期的状态（窗口在未来过期）
    QuotaRuntimeStateRecord active =
        new QuotaRuntimeStateRecord(
            null,
            "t1",
            "JOB",
            ownerCode,
            "SLIDING_WINDOW",
            Instant.now().minusSeconds(1800),
            Instant.now().plusSeconds(3600), // still valid
            3,
            null,
            Instant.now(),
            Instant.now(),
            null);
    quotaRuntimeStateMapper.insert(active);

    // reconcile() should not touch non-expired states
    quotaRuntimeResetScheduler.reconcile();

    QuotaRuntimeStateRecord afterReconcile =
        quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner("t1", "JOB", ownerCode);
    assertThat(afterReconcile).isNotNull();
    // peakBorrowedCount should remain unchanged since the window hasn't expired
    assertThat(afterReconcile.peakBorrowedCount()).isEqualTo(3);
  }

  @Test
  void evaluateAndReserveWithinWindowHoursAllows() {
    String ownerCode = "sched-eval-" + System.currentTimeMillis();

    // base=10, burst=5, active=8, requested=1 — within cap
    var result =
        quotaRuntimeStateService.evaluateAndReserve(
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
  void evaluateAndReserveOverBurstBlocks() {
    String ownerCode = "sched-burst-" + System.currentTimeMillis();

    // base=5, burst=2, active=10, requested=1 → borrowed=6 > burst=2
    var result =
        quotaRuntimeStateService.evaluateAndReserve(
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
