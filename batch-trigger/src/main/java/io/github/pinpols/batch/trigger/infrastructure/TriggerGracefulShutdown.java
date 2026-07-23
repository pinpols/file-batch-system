package io.github.pinpols.batch.trigger.infrastructure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Trigger 优雅停机协调器：
 *
 * <ul>
 *   <li>负责接收外部排水信号(REST API / ContextClosedEvent)
 *   <li>更新 {@link TriggerDrainState} 真值源(由 scheduler-agnostic 调用方直接读)
 *   <li>调用 Quartz {@code Scheduler.standby/start/shutdown}
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerGracefulShutdown implements ApplicationListener<ContextClosedEvent> {

  private final Scheduler scheduler;
  private final TriggerDrainState drainState;

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    try {
      if (scheduler.isShutdown()) {
        return;
      }
      startDraining("context-closed");
      log.info(
          "Trigger graceful shutdown — shutting down scheduler" + " (waitForJobsToComplete=true)");
      scheduler.shutdown(true);
      log.info("Trigger scheduler shutdown complete");
    } catch (SchedulerException e) {
      log.warn("Error during trigger graceful shutdown: {}", e.getMessage(), e);
    }
  }

  public void startDraining(String source) throws SchedulerException {
    if (drainState.startDraining(source)) {
      log.info("Trigger graceful shutdown — switching scheduler to standby, source={}", source);
      scheduler.standby();
    }
  }

  public void stopDraining(String source) throws SchedulerException {
    if (scheduler.isShutdown()) {
      return;
    }
    if (drainState.stopDraining(source)) {
      log.info("Trigger drain cancelled — restarting scheduler, source={}", source);
      scheduler.start();
    }
  }

  public boolean isDraining() {
    return drainState.isDraining();
  }

  public Map<String, Object> status() throws SchedulerException {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("draining", drainState.isDraining());
    status.put("drainingSince", drainState.getDrainingSince());
    status.put("reason", drainState.getReason());
    status.put(
        "schedulerStatus",
        scheduler.isShutdown()
            ? "SHUTDOWN"
            : scheduler.isInStandbyMode()
                ? "STANDBY"
                : scheduler.isStarted() ? "STARTED" : "STOPPED");
    // R-arch-audit-2026-05-23 P2: 返回不可变包装，防调用方修改 map 影响只读视图语义。
    return Collections.unmodifiableMap(status);
  }
}
