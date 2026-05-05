package com.example.batch.trigger.infrastructure;

import com.example.batch.common.time.BatchDateTimeSupport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/** Trigger 优雅停机：先将 Quartz Scheduler 切换到 standby 模式（停止触发新 job）， 再等待已触发的 job 执行完成后 shutdown。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerGracefulShutdown implements ApplicationListener<ContextClosedEvent> {

  private final Scheduler scheduler;
  private final AtomicBoolean draining = new AtomicBoolean(false);
  private volatile Instant drainingSince;
  private volatile String reason;

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
    if (draining.compareAndSet(false, true)) {
      drainingSince = BatchDateTimeSupport.utcNow();
      reason = source;
      log.info("Trigger graceful shutdown — switching scheduler to standby, source={}", source);
      scheduler.standby();
    }
  }

  public void stopDraining(String source) throws SchedulerException {
    if (scheduler.isShutdown()) {
      return;
    }
    if (draining.compareAndSet(true, false)) {
      log.info("Trigger drain cancelled — restarting scheduler, source={}", source);
      scheduler.start();
    }
    drainingSince = null;
    reason = source;
  }

  public boolean isDraining() {
    return draining.get();
  }

  public Map<String, Object> status() throws SchedulerException {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("draining", draining.get());
    status.put("drainingSince", drainingSince);
    status.put("reason", reason);
    status.put(
        "schedulerStatus",
        scheduler.isShutdown()
            ? "SHUTDOWN"
            : scheduler.isInStandbyMode()
                ? "STANDBY"
                : scheduler.isStarted() ? "STARTED" : "STOPPED");
    return status;
  }
}
