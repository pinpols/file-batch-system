package io.github.pinpols.batch.orchestrator.application.readiness;

import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * readiness 持久等待的周期推进器：在无 Quartz 回调时判定就绪、驱动恢复启动，并终结超窗等待。
 *
 * <p>与 Trigger 侧兜底扫尾器互补：Trigger 只负责本地 occurrence 超窗终结；Orchestrator 负责"恢复启动"与
 * 批日结算解除阻塞。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadinessWaitReconciler {

  private final ReadinessWaitService readinessWaitService;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.readiness-wait.sweep-interval-millis:60000}")
  @SchedulerLock(
      name = "readiness_wait_reconciler",
      lockAtMostFor = "PT2M",
      lockAtLeastFor = "PT5S")
  public void reconcile() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    try {
      ReadinessWaitService.SweepOutcome outcome = readinessWaitService.sweep(100);
      if (outcome.released() > 0 || outcome.timedOut() > 0) {
        log.info(
            "readiness wait sweep completed: released={}, timedOut={}, deferred={}",
            outcome.released(),
            outcome.timedOut(),
            outcome.deferred());
      }
    } catch (RuntimeException ex) {
      log.warn("readiness wait sweep failed; will retry next round", ex);
    }
  }
}
