package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitProperties;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("launch-wait 恢复: 同一轮按并发度并行启动")
class WorkflowLaunchWaitReconcilerParallelismTest {

    @Mock
    private TenantDirectoryMapper tenantDirectoryMapper;

    @Mock
    private WorkflowLaunchWaitService waitingService;

    @Mock
    private WorkflowRunLaunchService launchService;

    @Mock
    private OrchestratorGracefulShutdown gracefulShutdown;

    @Test
    @DisplayName("并发度大于一时两条等待同时进入 launch")
    void shouldLaunchClaimedEntriesConcurrently() {
        when(waitingService.claimDue(eq("ta"), eq(2), any()))
                .thenReturn(List.of(claim("req-a", 1L), claim("req-b", 2L)));
        when(waitingService.restoreRequest(eq("ta"), any()))
                .thenAnswer(invocation -> restored(invocation.getArgument(1)));
        CyclicBarrier barrier = new CyclicBarrier(2);
        when(launchService.launch(any())).thenAnswer(ignored -> {
            // 串行实现下第二个 launch 进不来，第一轮 await 超时并被当作可重试失败释放。
            barrier.await(5, TimeUnit.SECONDS);
            return new LaunchResponse("instance", "trace");
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            reconciler(pool).reconcileTenant("ta", 2);
        } finally {
            pool.shutdownNow();
        }

        verify(launchService, times(2)).launch(any());
        verify(waitingService, never()).release(any(), any(), any());
    }

    @Test
    @DisplayName("单条失败只释放该条，同轮其他条目照常启动")
    void shouldIsolateEntryFailure() {
        when(waitingService.claimDue(eq("ta"), eq(2), any()))
                .thenReturn(List.of(claim("req-a", 1L), claim("req-b", 2L)));
        when(waitingService.restoreRequest(eq("ta"), any()))
                .thenAnswer(invocation -> restored(invocation.getArgument(1)));
        doAnswer(invocation -> {
                    LaunchRequest request = invocation.getArgument(0);
                    if ("req-a".equals(request.requestId())) {
                        throw new IllegalStateException("launch boom");
                    }
                    return new LaunchResponse("instance", "trace");
                })
                .when(launchService)
                .launch(any());

        reconciler(Runnable::run).reconcileTenant("ta", 2);

        verify(launchService, times(2)).launch(any());
        verify(waitingService).release(eq("ta"), eq("req-a"), any());
    }

    private WorkflowLaunchWaitReconciler reconciler(Executor executor) {
        WorkflowLaunchWaitProperties properties = new WorkflowLaunchWaitProperties();
        properties.setBatchSize(100);
        properties.setResumeBatchSize(8);
        properties.setParallelism(4);
        return new WorkflowLaunchWaitReconciler(
                tenantDirectoryMapper, waitingService, launchService, properties, gracefulShutdown, executor);
    }

    private static LaunchRequest restored(Object claim) {
        WorkflowLaunchWaitClaim entry = (WorkflowLaunchWaitClaim) claim;
        return LaunchRequest.builder()
                .tenantId(entry.tenantId())
                .requestId(entry.requestId())
                .traceId("trace")
                .build();
    }

    private static WorkflowLaunchWaitClaim claim(String requestId, long workflowId) {
        return new WorkflowLaunchWaitClaim("ta", requestId, workflowId, LocalDate.of(2026, 1, 15), false, "{}");
    }
}
