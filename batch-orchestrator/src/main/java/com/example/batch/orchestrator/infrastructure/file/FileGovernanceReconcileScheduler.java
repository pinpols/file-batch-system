package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件治理——对象存储对账调度器。
 *
 * <p>默认每 60 秒触发一次，委托 {@link FileGovernanceScheduler#reconcileObjectStorage()}
 * 对比数据库文件记录与对象存储（OSS/NAS 等）的实际状态，修复差异并触发缺失补录。
 * ShedLock 锁名 {@code file_governance_reconcile}，最长持锁 3 分钟，最短持锁 30 秒，
 * 避免多节点同时执行对账导致重复写入。Orchestrator 优雅停机时跳过。
 */
@Component
@RequiredArgsConstructor
public class FileGovernanceReconcileScheduler {

  private final FileGovernanceScheduler fileGovernanceScheduler;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.file-governance.reconcile.poll-interval-millis:60000}")
  @SchedulerLock(
      name = "file_governance_reconcile",
      lockAtMostFor = "PT3M",
      lockAtLeastFor = "PT30S")
  public void reconcileObjectStorage() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    fileGovernanceScheduler.reconcileObjectStorage();
  }
}
