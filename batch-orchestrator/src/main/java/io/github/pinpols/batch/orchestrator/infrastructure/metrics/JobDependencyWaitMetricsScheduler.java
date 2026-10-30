package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitBacklog;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 发布等待数量和等待时长汇总，不带租户/作业标签。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobDependencyWaitMetricsScheduler {

  private final JobDependencyWaitMetricsQuery metricsQuery;
  private final TenantDirectoryMapper tenantDirectoryMapper;
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
      List<String> tenantIds = tenantDirectoryMapper.selectAllTenantIds();
      if (EmptyChecks.isNull(tenantIds)) {
        throw new IllegalStateException("tenant directory returned no tenant list");
      }
      long totalWaiting = 0L;
      long maxOldestAgeSeconds = 0L;
      for (String tenantId : tenantIds) {
        if (EmptyChecks.isBlank(tenantId)) {
          throw new IllegalStateException("tenant directory returned a blank tenant id");
        }
        JobDependencyWaitBacklog backlog = RlsTenantContextHolder.runWithTenant(
            tenantId, () -> metricsQuery.selectForTenant(tenantId));
        if (EmptyChecks.isNull(backlog)) {
          throw new IllegalStateException("job dependency wait metrics query returned no row");
        }
        totalWaiting = Math.addExact(totalWaiting, Math.max(0, backlog.waitingCount()));
        long nonNegativeOldestAgeSeconds = Math.max(0, backlog.oldestAgeSeconds());
        maxOldestAgeSeconds = Math.max(maxOldestAgeSeconds, nonNegativeOldestAgeSeconds);
      }
      waitingCount.set(totalWaiting);
      oldestAgeSeconds.set(maxOldestAgeSeconds);
    } catch (RuntimeException failure) {
      meterRegistry.counter("batch.job_dependency.wait.metrics.failures").increment();
      log.warn(
          "job dependency wait metrics sampling failed: {}",
          SwallowedExceptionLogger.summary(failure));
    }
  }
}
