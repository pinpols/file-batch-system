package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.application.service.WorkerDrainGovernanceService;
import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerDrainTimeoutScheduler {

  private final WorkerRegistryRepository workerRegistryRepository;
  private final WorkerDrainGovernanceService workerDrainGovernanceService;
  private final WorkerDrainProperties workerDrainProperties;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.worker.drain.check-interval-millis:15000}")
  @SchedulerLock(name = "worker_drain_timeout", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
  public void expireDrains() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!workerDrainProperties.isEnabled()) {
      return;
    }
    Instant now = Instant.now();
    List<WorkerRegistryRecord> draining =
        workerRegistryRepository.findByStatus(WorkerRegistryStatus.DRAINING.code());
    for (WorkerRegistryRecord worker : draining) {
      if (worker == null || worker.drainDeadlineAt() == null) {
        continue;
      }
      if (!worker.drainDeadlineAt().isAfter(now)) {
        workerDrainGovernanceService.takeoverAfterDrainTimeout(
            worker.tenantId(), worker.workerCode());
      }
    }
  }
}
