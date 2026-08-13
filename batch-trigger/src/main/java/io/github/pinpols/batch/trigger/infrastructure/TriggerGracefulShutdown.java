package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.lifecycle.BatchLifecyclePhases;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
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
public class TriggerGracefulShutdown
    implements ApplicationListener<ContextClosedEvent>,
        ApplicationEventPublisherAware,
        SmartLifecycle {

  private final Scheduler scheduler;
  private final TriggerDrainState drainState;
  private final AtomicBoolean lifecycleRunning = new AtomicBoolean(true);
  private ApplicationEventPublisher eventPublisher;

  /** 对外稳定的 Trigger 排水与调度器状态契约。 */
  public record TriggerDrainStatus(
      boolean draining, Instant drainingSince, String reason, String schedulerStatus) {}

  @Override
  public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
    this.eventPublisher = applicationEventPublisher;
  }

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    stop();
  }

  /** Quartz 必须先于数据源停止，避免 misfire 线程在连接池关闭后继续取连接。 */
  @Override
  public void stop() {
    if (!lifecycleRunning.compareAndSet(true, false)) {
      return;
    }
    try {
      if (scheduler.isShutdown()) {
        return;
      }
      startDraining("context-closed");
      log.info("Trigger scheduler standby complete");
    } catch (SchedulerException e) {
      log.warn("Error during trigger graceful shutdown: {}", e.getMessage(), e);
    }
  }

  @Override
  public void stop(Runnable callback) {
    try {
      stop();
    } finally {
      callback.run();
    }
  }

  @Override
  public void start() {
    lifecycleRunning.set(true);
  }

  @Override
  public boolean isRunning() {
    return lifecycleRunning.get();
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return BatchLifecyclePhases.FIRST_TO_STOP_RELAY;
  }

  public void startDraining(String source) throws SchedulerException {
    if (drainState.startDraining(source)) {
      publishReadiness(ReadinessState.REFUSING_TRAFFIC);
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
      publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
    }
  }

  private void publishReadiness(ReadinessState state) {
    if (EmptyChecks.isNull(eventPublisher)) {
      return;
    }
    try {
      AvailabilityChangeEvent.publish(eventPublisher, this, state);
    } catch (RuntimeException ex) {
      log.warn("failed to publish trigger readiness state {}: {}", state, ex.getMessage());
    }
  }

  public boolean isDraining() {
    return drainState.isDraining();
  }

  public TriggerDrainStatus status() throws SchedulerException {
    String schedulerStatus = scheduler.isShutdown()
        ? "SHUTDOWN"
        : scheduler.isInStandbyMode() ? "STANDBY" : scheduler.isStarted() ? "STARTED" : "STOPPED";
    return new TriggerDrainStatus(
        drainState.isDraining(),
        drainState.getDrainingSince(),
        drainState.getReason(),
        schedulerStatus);
  }
}
