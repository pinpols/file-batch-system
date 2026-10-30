package io.github.pinpols.batch.orchestrator.application.trigger;

import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期重投被 Kafka 消费重试耗尽丢弃的启动意图（{@link TriggerLaunchRecoveryService}）。
 *
 * <p>这是"无 Quartz/无消息"情况下的兜底路径：只要恢复锚点已落库，请求就不会因为 offset 前进而永久丢失。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerLaunchRecoveryScheduler {

  private final TriggerLaunchRecoveryService recoveryService;
  private final TriggerLaunchRecoveryProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final AtomicBoolean warnedOnFailure = new AtomicBoolean(false);

  @Scheduled(fixedDelayString = "${batch.trigger.launch-recovery.poll-interval-millis:30000}")
  @SchedulerLock(name = "trigger_launch_recovery", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
  public void recover() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    try {
      int recovered = recoveryService.recoverDue(properties.getBatchSize());
      if (recovered > 0) {
        log.info("trigger launch recovery processed {} record(s)", recovered);
      }
      warnedOnFailure.set(false);
    } catch (RuntimeException ex) {
      // 扫描失败不能影响下一轮；首个失败告警一次，避免持续打爆日志。
      if (warnedOnFailure.compareAndSet(false, true)) {
        log.warn("trigger launch recovery scan failed; will retry next round", ex);
      }
    }
  }
}
