package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.query.TriggerLaunchContinuityMetrics;
import io.github.pinpols.batch.orchestrator.config.TriggerLaunchContinuityMetricsProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 度量已接受但过期的请求，以及近期请求到实例的启动延迟。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerLaunchContinuityMetricsScheduler {

  private static final Duration COMPLETED_WINDOW = Duration.ofMinutes(15);

  private final TriggerRequestMapper triggerRequestMapper;
  private final MeterRegistry meterRegistry;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final TriggerLaunchContinuityMetricsProperties properties;

  private final AtomicLong staleAcceptedCount = new AtomicLong();
  private final AtomicLong oldestStaleAcceptedAgeSeconds = new AtomicLong();
  private final AtomicLong recentLaunchedCount = new AtomicLong();
  private final AtomicReference<Double> requestToInstanceP95Seconds = new AtomicReference<>(0.0);

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge("batch.trigger.launch.stale_accepted.count", staleAcceptedCount);
    meterRegistry.gauge(
        "batch.trigger.launch.stale_accepted.oldest_age.seconds", oldestStaleAcceptedAgeSeconds);
    meterRegistry.gauge("batch.trigger.launch.request_to_instance.samples", recentLaunchedCount);
    meterRegistry.gauge(
        "batch.trigger.launch.request_to_instance.p95.seconds",
        requestToInstanceP95Seconds,
        value -> value.get());
    meterRegistry.counter("batch.trigger.launch.continuity.metrics.failures");
  }

  @Scheduled(fixedDelayString = "${batch.trigger.launch.metrics.poll-interval-millis:30000}")
  @SchedulerLock(
      name = "trigger_launch_continuity_metrics",
      lockAtMostFor = "PT1M",
      lockAtLeastFor = "PT5S")
  public void sample() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    try {
      Instant now = BatchDateTimeSupport.utcNow();
      long staleAfterSeconds = Math.max(properties.getStaleAfterSeconds(), 1);
      Instant staleBefore = now.minusSeconds(staleAfterSeconds);
      Instant completedBefore = now.minus(COMPLETED_WINDOW);
      TriggerLaunchContinuityMetrics metrics =
          triggerRequestMapper.selectLaunchContinuityMetrics(staleBefore, completedBefore);
      if (EmptyChecks.isNull(metrics)) {
        throw new IllegalStateException("trigger launch continuity metrics query returned no row");
      }
      staleAcceptedCount.set(nonNegative(metrics.staleAcceptedCount()));
      oldestStaleAcceptedAgeSeconds.set(nonNegative(metrics.oldestStaleAcceptedAgeSeconds()));
      recentLaunchedCount.set(nonNegative(metrics.recentLaunchedCount()));
      double observedP95Seconds = EmptyChecks.isNull(metrics.requestToInstanceP95Seconds())
          ? 0.0
          : metrics.requestToInstanceP95Seconds();
      requestToInstanceP95Seconds.set(Math.max(0.0, observedP95Seconds));
    } catch (RuntimeException failure) {
      meterRegistry.counter("batch.trigger.launch.continuity.metrics.failures").increment();
      log.warn(
          "trigger launch continuity metrics sampling failed: {}",
          SwallowedExceptionLogger.summary(failure));
    }
  }

  private long nonNegative(Long value) {
    return EmptyChecks.isNull(value) ? 0L : Math.max(0L, value);
  }
}
