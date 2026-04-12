package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FileGovernanceLatencyScheduler {

  private final FileGovernanceScheduler fileGovernanceScheduler;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.file-governance.latency.poll-interval-millis:30000}")
  @SchedulerLock(name = "file_governance_latency", lockAtMostFor = "PT2M", lockAtLeastFor = "PT15S")
  public void collectLatencyMetrics() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    fileGovernanceScheduler.collectLatencyMetrics();
  }
}
