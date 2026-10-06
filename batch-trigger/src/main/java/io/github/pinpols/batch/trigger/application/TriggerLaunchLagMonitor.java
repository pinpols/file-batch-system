package io.github.pinpols.batch.trigger.application;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * 独立采样 trigger launch consumer group lag，为 Relay 自适应释放提供集群级下游压力信号。
 *
 * <p>采样只读 Kafka offset，不消费消息、不提交 offset。查询失败时发布未知样本，由发布治理器收缩到最小速率；
 * 采样在专用调度线程执行，不阻塞 outbox DB/Kafka 发布线程。
 *
 * <p>Kafka Admin 交互收敛在 {@link TriggerLaunchLagQueryPort} 适配器内，本类只负责调度、快照发布与降级。
 */
@Component
@Slf4j
public class TriggerLaunchLagMonitor {

  static final long UNKNOWN_LAG = TriggerLaunchLagQueryPort.UNKNOWN_LAG;

  private final TriggerLaunchLagQueryPort lagQuery;
  private final TriggerOutboxRelayProperties properties;
  private final ThreadPoolTaskScheduler scheduler;
  private final AtomicReference<LagSnapshot> snapshot =
      new AtomicReference<>(new LagSnapshot(UNKNOWN_LAG, 0L));
  private final AtomicLong sequence = new AtomicLong();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean stopping = new AtomicBoolean();
  private final AtomicReference<ScheduledFuture<?>> scheduledTask = new AtomicReference<>();

  public TriggerLaunchLagMonitor(
      TriggerLaunchLagQueryPort lagQuery,
      TriggerOutboxRelayProperties properties,
      MeterRegistry meterRegistry,
      @Qualifier("triggerLaunchLagMonitorScheduler") ThreadPoolTaskScheduler scheduler) {
    this.lagQuery = lagQuery;
    this.properties = properties;
    this.scheduler = scheduler;
    meterRegistry.gauge(
        "batch.trigger.launch.consumer.lag", snapshot, value -> value.get().lag());
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (!properties.isAdaptiveReleaseEnabled() || !started.compareAndSet(false, true)) {
      return;
    }
    scheduledTask.set(scheduler.scheduleWithFixedDelay(
        this::sampleSafely, Duration.ofMillis(properties.getLagSampleIntervalMillis())));
  }

  @EventListener(ContextClosedEvent.class)
  public void stop() {
    stopping.set(true);
    ScheduledFuture<?> task = scheduledTask.getAndSet(null);
    if (task != null) {
      task.cancel(true);
    }
  }

  LagSnapshot current() {
    return snapshot.get();
  }

  void sampleSafely() {
    if (stopping.get()) {
      return;
    }
    try {
      publishSnapshot(queryLag());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      if (!stopping.get()) {
        publishSnapshot(UNKNOWN_LAG);
      }
    } catch (ExecutionException | TimeoutException | RuntimeException exception) {
      if (!stopping.get()) {
        publishSnapshot(UNKNOWN_LAG);
        SwallowedExceptionLogger.warn(
            TriggerLaunchLagMonitor.class, "trigger-launch-consumer-lag-sample-failed", exception);
      }
    }
  }

  private long queryLag() throws InterruptedException, ExecutionException, TimeoutException {
    return lagQuery.sampleLag();
  }

  private void publishSnapshot(long lag) {
    snapshot.set(new LagSnapshot(lag, sequence.incrementAndGet()));
  }

  record LagSnapshot(long lag, long sequence) {}
}
