package com.example.batch.orchestrator.infrastructure.metrics;

import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Samples runtime consistency invariants that are too domain-specific for actuator. */
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
  private final AtomicLong invalidCapabilityTags = new AtomicLong();
  private final AtomicLong terminalInstancesWithActiveChildren = new AtomicLong();

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge("batch.worker.registry.stale_online.count", staleOnlineWorkers);
    meterRegistry.gauge(
        "batch.worker.registry.draining_overdue.count", drainingPastDeadlineWorkers);
    meterRegistry.gauge(
        "batch.worker.registry.decommissioned_active_claims.count",
        decommissionedWorkersWithActiveTasks);
    meterRegistry.gauge("batch.worker.capability_tags.invalid.count", invalidCapabilityTags);
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
      invalidCapabilityTags.set(workerRegistryMapper.countInvalidCapabilityTags());
      terminalInstancesWithActiveChildren.set(
          jobInstanceMapper.countTerminalInstancesWithActiveChildren());
    } catch (RuntimeException ex) {
      log.warn("runtime consistency metrics sampling failed: {}", ex.getMessage());
    }
  }
}
