package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 根运行离开 CREATED/RUNNING 并提交后，立刻补满该租户当前空闲的 launch-wait 槽位。
 *
 * <p>同一 Workflow、业务日和 dry-run 仍只有一个活跃 run。这里只去掉等下一次 5 秒轮询的空窗。
 */
@Slf4j
@Component
public class WorkflowLaunchWaitResumeTrigger {

  private final WorkflowLaunchWaitReconciler reconciler;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  public WorkflowLaunchWaitResumeTrigger(
      @Lazy WorkflowLaunchWaitReconciler reconciler,
      OrchestratorGracefulShutdown gracefulShutdown) {
    this.reconciler = reconciler;
    this.gracefulShutdown = gracefulShutdown;
  }

  public void resumeAfterCommit(String tenantId) {
    if (EmptyChecks.isBlank(tenantId)) {
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
    try {
      reconciler.reconcileTenant(tenantId);
    } catch (RuntimeException failure) {
      log.warn(
          "workflow launch wait resume after terminal run failed: tenantId={}, error={}",
          tenantId,
          SwallowedExceptionLogger.summary(failure));
    }
  }
}
