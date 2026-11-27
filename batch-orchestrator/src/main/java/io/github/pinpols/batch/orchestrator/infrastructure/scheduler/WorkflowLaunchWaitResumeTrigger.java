package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitProperties;
import io.github.pinpols.batch.orchestrator.config.OrchestratorAsyncConfiguration;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 根运行离开 CREATED/RUNNING 并提交后，立刻补满该租户当前空闲的 launch-wait 槽位。
 *
 * <p>同一 Workflow、业务日和 dry-run 仍只有一个活跃 run。这里只去掉等下一次 5 秒轮询的空窗。
 *
 * <p>补槽不在完成线程上同步执行：完成线程是终态消费线程，长时间跑 launch 会反向拖慢后续完成。改为提交到
 * launch-wait 恢复执行器，并按租户合并——同一租户已有一轮在执行时不再叠加。丢掉的触发由 5 秒定时扫描兜底。
 */
@Slf4j
@Component
public class WorkflowLaunchWaitResumeTrigger {

    private final WorkflowLaunchWaitReconciler reconciler;
    private final OrchestratorGracefulShutdown gracefulShutdown;
    private final WorkflowLaunchWaitProperties properties;
    private final Executor resumeExecutor;
    private final Set<String> resumingTenants = ConcurrentHashMap.newKeySet();

    public WorkflowLaunchWaitResumeTrigger(
            @Lazy WorkflowLaunchWaitReconciler reconciler,
            OrchestratorGracefulShutdown gracefulShutdown,
            WorkflowLaunchWaitProperties properties,
            @Qualifier(OrchestratorAsyncConfiguration.LAUNCH_WAIT_RESUME_EXECUTOR) Executor resumeExecutor) {
        this.reconciler = reconciler;
        this.gracefulShutdown = gracefulShutdown;
        this.properties = properties;
        this.resumeExecutor = resumeExecutor;
    }

    public void resumeAfterCommit(String tenantId) {
        if (EmptyValues.isBlank(tenantId)) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            resumeTenant(tenantId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                resumeTenant(tenantId);
            }
        });
    }

    private void resumeTenant(String tenantId) {
        if (gracefulShutdown.isDraining()) {
            return;
        }
        if (!resumingTenants.add(tenantId)) {
            // 该租户已有一轮在排队或执行：本轮结束会清除标记，不叠加新的扫描。
            return;
        }
        try {
            resumeExecutor.execute(() -> runResume(tenantId));
        } catch (RejectedExecutionException rejected) {
            resumingTenants.remove(tenantId);
            log.warn(
                    "workflow launch wait resume skipped: tenantId={}, error={}",
                    tenantId,
                    SwallowedExceptionLogger.summary(rejected));
        }
    }

    /**
     * 父任务只做认领和提交，不等待同池子任务；本轮全部结束（含空批、异常和拒绝提交）后清除租户去重标记。
     */
    private void runResume(String tenantId) {
        CompletableFuture<Void> round;
        try {
            round = reconciler.resumeTenantAsync(tenantId);
        } catch (RuntimeException failure) {
            resumingTenants.remove(tenantId);
            log.warn(
                    "workflow launch wait resume after terminal run failed: tenantId={}, error={}",
                    tenantId,
                    SwallowedExceptionLogger.summary(failure));
            return;
        }
        round.whenComplete((ignored, failure) -> {
            resumingTenants.remove(tenantId);
            if (failure != null) {
                log.warn(
                        "workflow launch wait resume round failed: tenantId={}, error={}",
                        tenantId,
                        SwallowedExceptionLogger.summary(failure));
            }
        });
    }
}
