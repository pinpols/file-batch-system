package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitProperties;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("launch-wait 恢复: 一轮补满全部空闲槽位，完成线程只排队不阻塞")
class WorkflowLaunchWaitResumeTriggerTest {

  @Mock
  private TenantDirectoryMapper tenantDirectoryMapper;

  @Mock
  private WorkflowLaunchWaitService waitingService;

  @Mock
  private WorkflowRunLaunchService launchService;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  @Test
  @DisplayName("一轮认领到的每个空闲槽位都会启动")
  void shouldLaunchEveryClaimedFreeSlotInOneRound() {
    WorkflowLaunchWaitReconciler reconciler = reconciler(Runnable::run);
    when(waitingService.claimDue(eq("ta"), eq(100), any()))
        .thenReturn(List.of(claim("req-a", 1L), claim("req-b", 2L)));
    when(waitingService.restoreRequest(eq("ta"), any())).thenAnswer(ignored -> restored("req"));

    reconciler.reconcileTenant("ta");

    verify(launchService, times(2)).launch(any());
  }

  @Test
  @DisplayName("终态后补槽只处理 resume-batch-size 条，其余留给定时扫描")
  void shouldResumeOnlyResumeBatchSizeAfterTerminal() {
    WorkflowLaunchWaitProperties properties = properties();
    properties.setResumeBatchSize(8);
    WorkflowLaunchWaitReconciler reconciler = reconciler(properties, Runnable::run);
    when(waitingService.claimDue(eq("ta"), anyInt(), any())).thenReturn(List.of());

    trigger(reconciler, properties, Runnable::run).resumeAfterCommit("ta");

    verify(waitingService).claimDue(eq("ta"), eq(8), any());
  }

  @Test
  @DisplayName("没有活动事务时，终态后立刻按租户再扫")
  void shouldResumeTenantImmediatelyWhenNoTransactionIsActive() {
    WorkflowLaunchWaitProperties properties = properties();
    WorkflowLaunchWaitReconciler reconciler = reconciler(properties, Runnable::run);
    when(waitingService.claimDue(any(), anyInt(), any())).thenReturn(List.of());

    trigger(reconciler, properties, Runnable::run).resumeAfterCommit("ta");

    verify(waitingService).claimDue(eq("ta"), anyInt(), any());
  }

  @Test
  @DisplayName("排水期间终态不再触发恢复")
  void shouldNotResumeWhileDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);
    WorkflowLaunchWaitProperties properties = properties();
    WorkflowLaunchWaitReconciler reconciler = reconciler(properties, Runnable::run);

    trigger(reconciler, properties, Runnable::run).resumeAfterCommit("ta");

    verify(waitingService, never()).claimDue(any(), anyInt(), any());
  }

  @Test
  @DisplayName("补槽提交到恢复执行器，完成线程立即返回")
  void shouldNotBlockTheCompletingThread() throws Exception {
    WorkflowLaunchWaitProperties properties = properties();
    WorkflowLaunchWaitReconciler reconciler = reconciler(properties, Runnable::run);
    CountDownLatch scanStarted = new CountDownLatch(1);
    CountDownLatch releaseScan = new CountDownLatch(1);
    when(waitingService.claimDue(any(), anyInt(), any())).thenAnswer(ignored -> {
      scanStarted.countDown();
      releaseScan.await(5, TimeUnit.SECONDS);
      return List.of();
    });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      trigger(reconciler, properties, pool).resumeAfterCommit("ta");

      assertThat(scanStarted.await(5, TimeUnit.SECONDS)).isTrue();
      // 扫描尚未结束时调用方已经返回：补槽不会占住完成线程。
      verify(waitingService, times(1)).claimDue(any(), anyInt(), any());
    } finally {
      releaseScan.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("同一租户重复触发合并为一轮扫描")
  void shouldCoalesceResumePerTenant() throws Exception {
    WorkflowLaunchWaitProperties properties = properties();
    WorkflowLaunchWaitReconciler reconciler = reconciler(properties, Runnable::run);
    CountDownLatch scanStarted = new CountDownLatch(1);
    CountDownLatch releaseScan = new CountDownLatch(1);
    when(waitingService.claimDue(any(), anyInt(), any())).thenAnswer(ignored -> {
      scanStarted.countDown();
      releaseScan.await(5, TimeUnit.SECONDS);
      return List.of();
    });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      WorkflowLaunchWaitResumeTrigger trigger = trigger(reconciler, properties, pool);
      trigger.resumeAfterCommit("ta");
      assertThat(scanStarted.await(5, TimeUnit.SECONDS)).isTrue();
      trigger.resumeAfterCommit("ta");

      verify(waitingService, timeout(300).times(1)).claimDue(any(), anyInt(), any());
    } finally {
      releaseScan.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("执行器拒绝时释放合并标记，后续触发仍可补槽")
  void shouldReleaseCoalescingFlagWhenExecutorRejects() {
    WorkflowLaunchWaitProperties properties = properties();
    WorkflowLaunchWaitReconciler reconciler = reconciler(properties, Runnable::run);
    when(waitingService.claimDue(any(), anyInt(), any())).thenReturn(List.of());
    AtomicInteger submissions = new AtomicInteger();
    Executor rejectingFirst = task -> {
      if (submissions.incrementAndGet() == 1) {
        throw new RejectedExecutionException("queue full");
      }
      task.run();
    };

    WorkflowLaunchWaitResumeTrigger trigger = trigger(reconciler, properties, rejectingFirst);
    trigger.resumeAfterCommit("ta");
    trigger.resumeAfterCommit("ta");

    verify(waitingService, times(1)).claimDue(eq("ta"), anyInt(), any());
  }

  private WorkflowLaunchWaitResumeTrigger trigger(
      WorkflowLaunchWaitReconciler reconciler,
      WorkflowLaunchWaitProperties properties,
      Executor executor) {
    return new WorkflowLaunchWaitResumeTrigger(reconciler, gracefulShutdown, properties, executor);
  }

  private WorkflowLaunchWaitReconciler reconciler(Executor executor) {
    return reconciler(properties(), executor);
  }

  private WorkflowLaunchWaitReconciler reconciler(
      WorkflowLaunchWaitProperties properties, Executor executor) {
    return new WorkflowLaunchWaitReconciler(
        tenantDirectoryMapper,
        waitingService,
        launchService,
        properties,
        gracefulShutdown,
        executor);
  }

  private static WorkflowLaunchWaitProperties properties() {
    WorkflowLaunchWaitProperties properties = new WorkflowLaunchWaitProperties();
    properties.setBatchSize(100);
    return properties;
  }

  private static LaunchRequest restored(String requestId) {
    return LaunchRequest.builder()
        .tenantId("ta")
        .requestId(requestId)
        .traceId("trace")
        .build();
  }

  private static WorkflowLaunchWaitClaim claim(String requestId, long workflowId) {
    return new WorkflowLaunchWaitClaim(
        "ta", requestId, workflowId, LocalDate.of(2026, 1, 15), false, "{}");
  }
}
