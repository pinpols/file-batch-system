package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitBacklog;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes aggregate wait count and age without tenant/job labels. */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobDependencyWaitMetricsScheduler {

  private final JobDependencyWaitingLaunchMapper waitingMapper;
  private final MeterRegistry meterRegistry;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  private final AtomicLong waitingCount = new AtomicLong();
  private final AtomicLong oldestAgeSeconds = new AtomicLong();

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge("batch.job_dependency.waiting", waitingCount);
    meterRegistry.gauge("batch.job_dependency.wait.oldest_age_seconds", oldestAgeSeconds);
    meterRegistry.counter("batch.job_dependency.wait.metrics.failures");
  }

  @Scheduled(fixedDelayString = "${batch.metrics.job-dependency-wait.poll-interval-millis:30000}")
  @SchedulerLock(
      name = "job_dependency_wait_metrics",
      lockAtMostFor = "PT1M",
      lockAtLeastFor = "PT5S")
  public void sample() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    try {
      JobDependencyWaitBacklog backlog = waitingMapper.selectBacklogMetrics();
      if (EmptyChecks.isNull(backlog)) {
        throw new IllegalStateException("job dependency wait metrics query returned no row");
      }
      waitingCount.set(Math.max(0, backlog.waitingCount()));
      oldestAgeSeconds.set(Math.max(0, backlog.oldestAgeSeconds()));
    } catch (RuntimeException failure) {
      meterRegistry.counter("batch.job_dependency.wait.metrics.failures").increment();
      log.warn(
          "job dependency wait metrics sampling failed: {}",
          SwallowedExceptionLogger.summary(failure));
    }
  }
}
