package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitProperties;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.config.OrchestratorAsyncConfiguration;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按租户恢复名额等待，不暂停 Kafka 分区，也不更改原业务日期。
 *
 * <p>同一轮内不同 requestId 并发启动：每条等待都要跑一次完整 launch 事务，串行循环会让单租户恢复吞吐
 * 退化到单线程水平。租约（{@code claimLeaseSeconds}）保证同一 requestId 不会被重复认领。
 */
@Slf4j
@Component
public class WorkflowLaunchWaitReconciler {
    private final TenantDirectoryMapper tenantDirectoryMapper;
    private final WorkflowLaunchWaitService waitingService;
    private final WorkflowRunLaunchService launchService;
    private final WorkflowLaunchWaitProperties properties;
    private final OrchestratorGracefulShutdown gracefulShutdown;
    private final Executor resumeExecutor;

    public WorkflowLaunchWaitReconciler(
            TenantDirectoryMapper tenantDirectoryMapper,
            WorkflowLaunchWaitService waitingService,
            WorkflowRunLaunchService launchService,
            WorkflowLaunchWaitProperties properties,
            OrchestratorGracefulShutdown gracefulShutdown,
            @Qualifier(OrchestratorAsyncConfiguration.LAUNCH_WAIT_RESUME_EXECUTOR) Executor resumeExecutor) {
        this.tenantDirectoryMapper = tenantDirectoryMapper;
        this.waitingService = waitingService;
        this.launchService = launchService;
        this.properties = properties;
        this.gracefulShutdown = gracefulShutdown;
        this.resumeExecutor = resumeExecutor;
    }

    @Scheduled(fixedDelayString = "${batch.workflow.launch-wait.poll-interval-millis:5000}")
    @SchedulerLock(name = "workflow_launch_wait_reconcile", lockAtMostFor = "PT5M")
    public void reconcile() {
        if (gracefulShutdown.isDraining()) {
            return;
        }
        for (String tenantId : tenantDirectoryMapper.selectRunnableTenantIds()) {
            try {
                reconcileTenant(tenantId);
            } catch (RuntimeException failure) {
                log.warn(
                        "workflow launch wait scan failed: tenantId={}, error={}",
                        tenantId,
                        SwallowedExceptionLogger.summary(failure));
            }
        }
    }

    public void reconcileTenant(String tenantId) {
        reconcileTenant(tenantId, properties.getBatchSize());
    }

    /**
     * 终态补槽的异步入口：在本线程完成认领后，把逐项恢复提交给恢复池，调用线程立即返回。
     *
     * <p>返回的 future 在本轮已提交任务全部结束时完成，空批立即完成。调用方（尤其是恢复池自己的工作线程）不得对它调用
     * {@code join()}，否则会回到“父任务占着线程等同池子任务”的阻塞模型。
     */
    public CompletableFuture<Void> resumeTenantAsync(String tenantId) {
        return RlsTenantContextHolder.runWithTenant(
                tenantId, () -> submitTenantResumeInContext(tenantId, properties.getResumeBatchSize()));
    }

    public void reconcileTenant(String tenantId, int limit) {
        RlsTenantContextHolder.runWithTenant(
                tenantId, () -> submitTenantResumeInContext(tenantId, limit).join());
    }

    private CompletableFuture<Void> submitTenantResumeInContext(String tenantId, int limit) {
        Instant retryCutoffBaseTime = BatchDateTimeSupport.utcNow();
        long retryDelaySeconds = properties.getRetryDelaySeconds();
        Instant claimLease = retryCutoffBaseTime.plusSeconds(properties.getClaimLeaseSeconds());
        List<WorkflowLaunchWaitClaim> waiting = waitingService.claimDue(tenantId, limit, claimLease);
        if (EmptyChecks.isEmpty(waiting)) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Void>> launches = new ArrayList<>(waiting.size());
        for (WorkflowLaunchWaitClaim entry : waiting) {
            CompletableFuture<Void> launch = new CompletableFuture<>();
            try {
                resumeExecutor.execute(() -> runClaim(tenantId, entry, retryDelaySeconds, launch));
            } catch (RejectedExecutionException rejected) {
                // 提交失败时立即释放认领，避免等待行占着租约直到超时；本轮仍按完成收尾。
                releaseForRetryQuietly(tenantId, entry.requestId(), retryDelaySeconds);
                launch.complete(null);
                log.warn(
                        "workflow launch wait resume task rejected: tenantId={}, requestId={}, error={}",
                        tenantId,
                        entry.requestId(),
                        SwallowedExceptionLogger.summary(rejected));
            }
            launches.add(launch);
        }
        return CompletableFuture.allOf(launches.toArray(CompletableFuture[]::new));
    }

    private void runClaim(
            String tenantId, WorkflowLaunchWaitClaim entry, long retryDelaySeconds, CompletableFuture<Void> launch) {
        try {
            // Worker 线程没有调用方的租户上下文，RLS 必须逐条绑定。
            RlsTenantContextHolder.runWithTenant(
                    tenantId, () -> reconcileClaimInTenant(tenantId, entry, retryDelaySeconds));
        } catch (RuntimeException unexpected) {
            log.warn(
                    "workflow launch wait resume task failed: tenantId={}, requestId={}, error={}",
                    tenantId,
                    entry.requestId(),
                    SwallowedExceptionLogger.summary(unexpected));
        } finally {
            launch.complete(null);
        }
    }

    private void reconcileClaimInTenant(String tenantId, WorkflowLaunchWaitClaim entry, long retryDelaySeconds) {
        LaunchRequest request;
        try {
            request = waitingService.restoreRequest(tenantId, entry);
        } catch (BizException malformedPayload) {
            waitingService.reject(tenantId, entry.requestId());
            log.warn(
                    "workflow launch wait payload rejected: tenantId={}, requestId={}, error={}",
                    tenantId,
                    entry.requestId(),
                    SwallowedExceptionLogger.summary(malformedPayload));
            return;
        } catch (RuntimeException unavailableProtection) {
            releaseForRetry(tenantId, entry.requestId(), retryDelaySeconds);
            log.warn(
                    "workflow launch wait decrypt deferred: tenantId={}, requestId={}, error={}",
                    tenantId,
                    entry.requestId(),
                    SwallowedExceptionLogger.summary(unavailableProtection));
            return;
        }
        try {
            launchService.launch(request);
        } catch (BizException rejected) {
            waitingService.reject(tenantId, entry.requestId());
            log.warn(
                    "workflow launch wait rejected: tenantId={}, requestId={}, error={}",
                    tenantId,
                    entry.requestId(),
                    SwallowedExceptionLogger.summary(rejected));
        } catch (RuntimeException retryable) {
            releaseForRetry(tenantId, entry.requestId(), retryDelaySeconds);
            log.warn(
                    "workflow launch wait retry deferred: tenantId={}, requestId={}, error={}",
                    tenantId,
                    entry.requestId(),
                    SwallowedExceptionLogger.summary(retryable));
        }
    }

    private void releaseForRetry(String tenantId, String requestId, long retryDelaySeconds) {
        waitingService.release(
                tenantId, requestId, BatchDateTimeSupport.utcNow().plusSeconds(retryDelaySeconds));
    }

    private void releaseForRetryQuietly(String tenantId, String requestId, long retryDelaySeconds) {
        try {
            releaseForRetry(tenantId, requestId, retryDelaySeconds);
        } catch (RuntimeException failure) {
            // 释放失败时由认领租约到期后的定时扫描恢复，不阻断本轮收尾。
            log.warn(
                    "workflow launch wait release after rejected resume failed:"
                            + " tenantId={}, requestId={}, error={}",
                    tenantId,
                    requestId,
                    SwallowedExceptionLogger.summary(failure));
        }
    }
}
