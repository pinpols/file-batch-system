package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.orchestrator.application.service.governance.JobMonitoringAlertEmissionService;
import io.github.pinpols.batch.orchestrator.application.service.governance.JobMonitoringAlertEmissionService.Violation;
import io.github.pinpols.batch.orchestrator.config.SlaGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 纯旁路作业监控扫描器；扫描和告警写入失败不会改变或阻断作业实例状态推进。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobMonitoringScheduler {

  private static final String VIOLATION_TYPE_TAG = "violation_type";

  private final JobMonitoringMapper jobMonitoringMapper;
  private final JobMonitoringAlertEmissionService emissionService;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final MeterRegistry meterRegistry;
  private final SlaGovernanceProperties properties;

  @Scheduled(
      fixedDelayString = "${batch.sla.job-monitoring.poll-interval-millis:30000}",
      scheduler = "jobMonitoringTaskScheduler")
  @SchedulerLock(name = "job_monitoring_scan", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
  public void scan() {
    if (!properties.getJobMonitoring().isEnabled() || gracefulShutdown.isDraining()) {
      return;
    }
    int batchSize = properties.getJobMonitoring().getBatchSize();
    scanCategory(
        Violation.RUNNING_TOO_LONG, () -> jobMonitoringMapper.selectRunningTooLong(batchSize));
    scanCategory(Violation.NOT_STARTED, () -> jobMonitoringMapper.selectNotStarted(batchSize));
    scanCategory(
        Violation.NOT_COMPLETED_BY_DEADLINE,
        () -> jobMonitoringMapper.selectNotCompletedByDeadline(batchSize));
    scanCategory(
        Violation.FAILED_PARTITION,
        () -> jobMonitoringMapper.selectFinalPartitionFailures(
            batchSize, properties.getJobMonitoring().getFailedPartitionLookbackSeconds()));
  }

  private void scanCategory(
      Violation violation, Supplier<List<JobMonitoringAlertCandidate>> query) {
    long startedAt = System.nanoTime();
    String outcome = "success";
    try {
      List<JobMonitoringAlertCandidate> candidates = null;
      boolean querySucceeded = false;
      long queryStartedAt = System.nanoTime();
      try {
        candidates = query.get();
        querySucceeded = true;
      } catch (RuntimeException exception) {
        outcome = "failure";
        meterRegistry
            .counter(
                "batch.job.monitoring.scan.failures.total", VIOLATION_TYPE_TAG, violation.name())
            .increment();
        SwallowedExceptionLogger.warn(
            JobMonitoringScheduler.class, "catch:monitoring-candidate-scan", exception);
      } finally {
        Timer.builder("batch.job.monitoring.query.duration")
            .tag(VIOLATION_TYPE_TAG, violation.name())
            .tag("outcome", outcome)
            .register(meterRegistry)
            .record(System.nanoTime() - queryStartedAt, TimeUnit.NANOSECONDS);
      }
      if (querySucceeded) {
        meterRegistry
            .summary("batch.job.monitoring.scan.candidates", VIOLATION_TYPE_TAG, violation.name())
            .record(candidates == null ? 0 : candidates.size());
        scanCandidates(candidates, violation);
      }
    } finally {
      Timer.builder("batch.job.monitoring.scan.duration")
          .tag(VIOLATION_TYPE_TAG, violation.name())
          .tag("outcome", outcome)
          .register(meterRegistry)
          .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }
  }

  private void scanCandidates(List<JobMonitoringAlertCandidate> candidates, Violation violation) {
    if (candidates == null || candidates.isEmpty()) {
      return;
    }
    for (JobMonitoringAlertCandidate candidate : candidates) {
      if (candidate == null || candidate.tenantId() == null || candidate.jobInstanceId() == null) {
        continue;
      }
      try {
        if (emissionService.emitOnce(candidate, violation)) {
          meterRegistry
              .counter("batch.job.monitoring.violation.total", VIOLATION_TYPE_TAG, violation.name())
              .increment();
        }
      } catch (RuntimeException exception) {
        meterRegistry
            .counter(
                "batch.job.monitoring.alert.failures.total", VIOLATION_TYPE_TAG, violation.name())
            .increment();
        SwallowedExceptionLogger.warn(
            JobMonitoringScheduler.class, "catch:monitoringAlertEmission", exception);
      }
    }
  }
}
