package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import static io.github.pinpols.batch.orchestrator.config.OrchestratorAsyncConfiguration.JOB_MONITORING_SCHEDULER;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.config.SlaGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowMonitoringQuery;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按租户扫描根级机会与运行；单项告警失败不会阻断其它机会或业务主链。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowMonitoringScheduler {
  private final SlaGovernanceProperties properties;
  private final TenantDirectoryMapper tenantDirectoryMapper;
  private final WorkflowMonitoringCandidateService candidateService;
  private final WorkflowMonitoringAlertEmissionService emissionService;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final MeterRegistry meterRegistry;
  private final Map<String, ScanSignal> scanSignals = Map.of(
      "directory", new ScanSignal(),
      "tenant_scan", new ScanSignal(),
      "emit", new ScanSignal());
  private boolean healthGaugesRegistered;

  @Scheduled(
      fixedDelayString = "${batch.sla.workflow-monitoring.poll-interval-millis:30000}",
      scheduler = JOB_MONITORING_SCHEDULER)
  @SchedulerLock(name = "workflow_monitoring_scan", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
  public void scan() {
    registerHealthGauges();
    if (!properties.getWorkflowMonitoring().isEnabled() || gracefulShutdown.isDraining()) {
      return;
    }
    List<String> tenantIds;
    try {
      tenantIds = tenantDirectoryMapper.selectAllTenantIds();
    } catch (RuntimeException failure) {
      recordFailure("directory", failure);
      return;
    }
    boolean completeScanSucceeded = true;
    for (String tenantId : tenantIds) {
      try {
        boolean tenantScanSucceeded =
            RlsTenantContextHolder.runWithTenant(tenantId, () -> scanTenant(tenantId));
        if (!tenantScanSucceeded) {
          completeScanSucceeded = false;
        }
      } catch (RuntimeException failure) {
        recordFailure("tenant_scan", failure);
        completeScanSucceeded = false;
      }
    }
    if (completeScanSucceeded) {
      recordSuccessfulScan();
    }
  }

  private boolean scanTenant(String tenantId) {
    WorkflowMonitoringQuery query = WorkflowMonitoringQuery.builder()
        .tenantId(tenantId)
        .limit(properties.getWorkflowMonitoring().getBatchSize())
        .build();
    List<WorkflowMonitoringAlertCandidate> candidates = candidateService.select(query);
    boolean tenantScanSucceeded = true;
    for (WorkflowMonitoringAlertCandidate candidate : candidates) {
      try {
        if (emissionService.emitOnce(candidate)) {
          meterRegistry
              .counter(
                  "batch.workflow.monitoring.violation.total",
                  "violation_type",
                  candidate.violation().name())
              .increment();
        }
      } catch (RuntimeException failure) {
        recordFailure("emit", failure);
        tenantScanSucceeded = false;
      }
    }
    return tenantScanSucceeded;
  }

  private void recordFailure(String stage, RuntimeException failure) {
    scanSignals.get(stage).recordFailure();
    meterRegistry
        .counter("batch.workflow.monitoring.failures.total", "stage", stage)
        .increment();
    String logLabel =
        switch (stage) {
          case "directory" -> "workflow-monitoring-directory";
          case "tenant_scan" -> "workflow-monitoring-tenant-scan";
          case "emit" -> "workflow-monitoring-emit";
          default -> throw new IllegalArgumentException("Unknown monitoring stage: " + stage);
        };
    SwallowedExceptionLogger.warn(WorkflowMonitoringScheduler.class, logLabel, failure);
  }

  private void recordSuccessfulScan() {
    scanSignals.values().forEach(ScanSignal::recordSuccess);
  }

  @PostConstruct
  synchronized void registerHealthGauges() {
    if (healthGaugesRegistered) {
      return;
    }
    Gauge.builder(
            "batch.workflow.monitoring.enabled",
            properties,
            value -> value.getWorkflowMonitoring().isEnabled() ? 1.0d : 0.0d)
        .register(meterRegistry);
    Gauge.builder(
            "batch.workflow.monitoring.poll.interval.seconds",
            properties,
            value -> value.getWorkflowMonitoring().getPollIntervalMillis() / 1000.0d)
        .register(meterRegistry);
    scanSignals.forEach((stage, signal) -> {
      Gauge.builder(
              "batch.workflow.monitoring.last.failure.timestamp.seconds",
              signal,
              ScanSignal::lastFailureTimestampSeconds)
          .tag("stage", stage)
          .register(meterRegistry);
      Gauge.builder(
              "batch.workflow.monitoring.last.success.timestamp.seconds",
              signal,
              ScanSignal::lastSuccessTimestampSeconds)
          .tag("stage", stage)
          .register(meterRegistry);
    });
    healthGaugesRegistered = true;
  }

  private static final class ScanSignal {
    private final AtomicLong lastFailureAtMillis = new AtomicLong();
    private final AtomicLong lastSuccessAtMillis = new AtomicLong();

    private double lastFailureTimestampSeconds() {
      return lastFailureAtMillis.get() / 1000.0d;
    }

    private double lastSuccessTimestampSeconds() {
      return lastSuccessAtMillis.get() / 1000.0d;
    }

    private void recordFailure() {
      long previous = Math.max(lastFailureAtMillis.get(), lastSuccessAtMillis.get());
      lastFailureAtMillis.set(Math.max(System.currentTimeMillis(), previous + 1));
    }

    private void recordSuccess() {
      long previous = Math.max(lastFailureAtMillis.get(), lastSuccessAtMillis.get());
      lastSuccessAtMillis.set(Math.max(System.currentTimeMillis(), previous + 1));
    }
  }
}
