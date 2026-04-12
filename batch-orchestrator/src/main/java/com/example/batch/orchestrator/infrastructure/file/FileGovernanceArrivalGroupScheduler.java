package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FileGovernanceArrivalGroupScheduler {

  private final FileGovernanceScheduler fileGovernanceScheduler;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.file-governance.arrival.poll-interval-millis:30000}")
  @SchedulerLock(
      name = "file_governance_arrival_group",
      lockAtMostFor = "PT2M",
      lockAtLeastFor = "PT15S")
  public void manageFileArrivalGroups() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    fileGovernanceScheduler.manageFileArrivalGroups();
  }
}
