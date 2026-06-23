package io.github.pinpols.batch.orchestrator.infrastructure.retry;

import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * V90 死信自动重放调度器。
 *
 * <p>默认每 30 秒触发一次，扫描 {@code error_class=SYSTEM AND replay_status IN (NEW, FAILED) AND
 * next_replay_at &lt;= now AND replay_count &lt; max_replay_count} 的死信记录，逐条调用 {@link
 * RetryGovernanceService#autoRetryDueDeadLetters()}。
 *
 * <p>BUSINESS 类的死信（文件缺失、渠道未配等不会自愈的硬错）不参与自动重放，仅人工 {@code /internal/dead-letters/{id}/replay} 触发；超过
 * {@code max_replay_count} 后 service 转 GIVE_UP。
 */
@Component
@RequiredArgsConstructor
public class DeadLetterAutoRetryScheduler {

  private final RetryGovernanceService retryGovernanceService;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final AtomicBoolean running = new AtomicBoolean(false);

  @Scheduled(fixedDelayString = "${batch.retry.dead-letter-poll-interval-millis:30000}")
  @SchedulerLock(name = "dead_letter_auto_retry", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
  public void poll() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      retryGovernanceService.autoRetryDueDeadLetters();
    } finally {
      running.set(false);
    }
  }
}
