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

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitProperties;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;

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

    @Test
    @DisplayName("恢复池并发度为 1 时，多租户终态补槽仍能全部推进")
    void shouldProgressAllTenantsWithSingleThreadResumePool() throws Exception {
        assertMultiTenantResumeProgress(1);
    }

    @Test
    @DisplayName("恢复池默认并发度 4 时，多租户终态补槽仍能全部推进")
    void shouldProgressAllTenantsWithDefaultParallelism() throws Exception {
        assertMultiTenantResumeProgress(4);
    }

    private void assertMultiTenantResumeProgress(int parallelism) throws Exception {
        WorkflowLaunchWaitProperties properties = properties();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        CountDownLatch launched = new CountDownLatch(4);
        try {
            when(waitingService.claimDue(any(), anyInt(), any()))
                    .thenAnswer(invocation -> List.of(claim("req-" + invocation.getArgument(0), 1L)));
            when(waitingService.restoreRequest(any(), any()))
                    .thenAnswer(
                            invocation -> restored(((WorkflowLaunchWaitClaim) invocation.getArgument(1)).requestId()));
            when(launchService.launch(any())).thenAnswer(invocation -> {
                launched.countDown();
                return new LaunchResponse("instance", "trace");
            });
            WorkflowLaunchWaitResumeTrigger trigger = trigger(reconciler(properties, pool), properties, pool);

            trigger.resumeAfterCommit("ta");
            trigger.resumeAfterCommit("tb");
            trigger.resumeAfterCommit("tc");
            trigger.resumeAfterCommit("td");

            assertThat(launched.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        verify(launchService, times(4)).launch(any());
    }

    @Test
    @DisplayName("一轮结束后清除租户标记，下一轮终态仍能再次补槽")
    void shouldAllowNextRoundAfterCompletedRound() throws Exception {
        WorkflowLaunchWaitProperties properties = properties();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch firstRound = new CountDownLatch(1);
        try {
            when(waitingService.claimDue(eq("ta"), anyInt(), any())).thenReturn(List.of(claim("req-a", 1L)));
            when(waitingService.restoreRequest(eq("ta"), any())).thenReturn(restored("req-a"));
            when(launchService.launch(any())).thenAnswer(invocation -> {
                firstRound.countDown();
                return new LaunchResponse("instance", "trace");
            });
            WorkflowLaunchWaitResumeTrigger trigger = trigger(reconciler(properties, pool), properties, pool);

            trigger.resumeAfterCommit("ta");
            assertThat(firstRound.await(5, TimeUnit.SECONDS)).isTrue();
            // 同池排队屏障：上一轮子任务及其收尾先于屏障执行，确保去重标记已清除。
            pool.submit(() -> {}).get(5, TimeUnit.SECONDS);

            trigger.resumeAfterCommit("ta");
            verify(launchService, timeout(5000).times(2)).launch(any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("逐项提交被拒绝时释放认领，本轮收尾后下一轮仍可补槽")
    void shouldReleaseClaimsAndClearMarkerWhenEntrySubmissionRejected() {
        WorkflowLaunchWaitProperties properties = properties();
        when(waitingService.claimDue(eq("ta"), anyInt(), any()))
                .thenReturn(List.of(claim("req-a", 1L), claim("req-b", 2L)));
        // 父任务无 RLS 上下文，内联执行；逐项提交时 RLS 已绑定，模拟队列满/停机拒绝。
        Executor rejectingEntries = task -> {
            if (RlsTenantContextHolder.get() == null) {
                task.run();
                return;
            }
            throw new RejectedExecutionException("queue full");
        };

        WorkflowLaunchWaitResumeTrigger trigger =
                trigger(reconciler(properties, rejectingEntries), properties, rejectingEntries);
        trigger.resumeAfterCommit("ta");
        trigger.resumeAfterCommit("ta");

        verify(waitingService, times(2)).claimDue(eq("ta"), anyInt(), any());
        verify(waitingService, times(4)).release(eq("ta"), any(), any());
        verify(launchService, never()).launch(any());
    }

    private WorkflowLaunchWaitResumeTrigger trigger(
            WorkflowLaunchWaitReconciler reconciler, WorkflowLaunchWaitProperties properties, Executor executor) {
        return new WorkflowLaunchWaitResumeTrigger(reconciler, gracefulShutdown, properties, executor);
    }

    private WorkflowLaunchWaitReconciler reconciler(Executor executor) {
        return reconciler(properties(), executor);
    }

    private WorkflowLaunchWaitReconciler reconciler(WorkflowLaunchWaitProperties properties, Executor executor) {
        return new WorkflowLaunchWaitReconciler(
                tenantDirectoryMapper, waitingService, launchService, properties, gracefulShutdown, executor);
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
        return new WorkflowLaunchWaitClaim("ta", requestId, workflowId, LocalDate.of(2026, 1, 15), false, "{}");
    }
}
