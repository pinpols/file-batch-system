package com.example.batch.orchestrator.infrastructure.retry;

import com.example.batch.orchestrator.application.service.RetryGovernanceService;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 重试计划轮询调度器。
 *
 * <p>默认每 10 秒触发一次，将到期的重试记录下发给 {@link RetryGovernanceService#dispatchDueRetries()}
 * 重新进入调度链路。ShedLock 锁名 {@code retry_schedule_poll}，最长持锁 1 分钟，最短持锁 5 秒。
 * 使用 {@link java.util.concurrent.atomic.AtomicBoolean} 防止单节点并发重入；
 * Orchestrator 优雅停机时直接跳过。
 */
@Component
@RequiredArgsConstructor
public class RetryScheduleScheduler {

  private final RetryGovernanceService retryGovernanceService;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final AtomicBoolean running = new AtomicBoolean(false);

  @Scheduled(fixedDelayString = "${batch.retry.poll-interval-millis:10000}")
  @SchedulerLock(name = "retry_schedule_poll", lockAtMostFor = "PT1M", lockAtLeastFor = "PT5S")
  public void poll() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      retryGovernanceService.dispatchDueRetries();
    } finally {
      running.set(false);
    }
  }
}
