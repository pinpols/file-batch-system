package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件治理——到达分组管理调度器。
 *
 * <p>默认每 30 秒触发一次，委托 {@link FileGovernanceScheduler#manageFileArrivalGroups()}
 * 对已到达的文件按批次窗口和分组规则进行聚合与状态推进。
 * ShedLock 锁名 {@code file_governance_arrival_group}，最长持锁 2 分钟，最短持锁 15 秒。
 * Orchestrator 优雅停机时跳过执行。
 */
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
