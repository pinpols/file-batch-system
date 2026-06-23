package io.github.pinpols.batch.orchestrator.infrastructure.metrics;

import io.github.pinpols.batch.orchestrator.config.WorkerDrainProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 采样运行态一致性不变量(这些指标过于业务化,不适合放进 actuator)。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeConsistencyMetricsScheduler {

  private final WorkerRegistryMapper workerRegistryMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final WorkerDrainProperties workerDrainProperties;
  private final MeterRegistry meterRegistry;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  private final AtomicLong staleOnlineWorkers = new AtomicLong();
  private final AtomicLong drainingPastDeadlineWorkers = new AtomicLong();
  private final AtomicLong decommissionedWorkersWithActiveTasks = new AtomicLong();
  private final AtomicLong terminalInstancesWithActiveChildren = new AtomicLong();

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge("batch.worker.registry.stale_online.count", staleOnlineWorkers);
    meterRegistry.gauge(
        "batch.worker.registry.draining_overdue.count", drainingPastDeadlineWorkers);
    meterRegistry.gauge(
        "batch.worker.registry.decommissioned_active_claims.count",
        decommissionedWorkersWithActiveTasks);
    // batch.worker.capability_tags.invalid.count 由 WorkerCapabilityTagsAuditScheduler 独占注册，
    // 避免 Micrometer “Gauge already registered” 噪声；该指标仍以审计调度结果为准。
    meterRegistry.gauge(
        "batch.job_instance.terminal_active_children.count", terminalInstancesWithActiveChildren);
  }

  @Scheduled(
      fixedDelayString =
          "${batch.metrics.runtime-consistency.poll-interval-millis:"
              + "${batch.metrics.worker-registry.poll-interval-millis:30000}}")
  @SchedulerLock(
      name = "runtime_consistency_metrics",
      lockAtMostFor = "PT1M",
      lockAtLeastFor = "PT10S")
  public void sample() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    try {
      long effectiveSeconds =
          (long) workerDrainProperties.getHeartbeatTimeoutSeconds()
              + workerDrainProperties.getHeartbeatGraceSeconds();
      staleOnlineWorkers.set(workerRegistryMapper.countStaleOnline(effectiveSeconds));
      drainingPastDeadlineWorkers.set(workerRegistryMapper.countDrainingPastDeadline());
      decommissionedWorkersWithActiveTasks.set(
          workerRegistryMapper.countDecommissionedWithActiveTasks());
      terminalInstancesWithActiveChildren.set(
          jobInstanceMapper.countTerminalInstancesWithActiveChildren());
    } catch (RuntimeException ex) {
      log.warn("runtime consistency metrics sampling failed: {}", ex.getMessage());
    }
  }
}
