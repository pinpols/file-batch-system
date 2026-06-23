package io.github.pinpols.batch.orchestrator.infrastructure.file;

import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件治理——归档清理调度器。
 *
 * <p>默认每 60 秒触发一次，委托 {@link FileGovernanceScheduler#cleanupArchivedFiles()}
 * 删除已超过保留期的归档文件记录及对应的存储对象，释放存储空间。 ShedLock 锁名 {@code file_governance_archive_cleanup}，最长持锁 3
 * 分钟，最短持锁 30 秒。 Orchestrator 优雅停机时跳过执行。
 */
@Component
@RequiredArgsConstructor
public class FileGovernanceArchiveCleanupScheduler {

  private final FileGovernanceScheduler fileGovernanceScheduler;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.file-governance.archive.cleanup-interval-millis:60000}")
  @SchedulerLock(
      name = "file_governance_archive_cleanup",
      lockAtMostFor = "PT3M",
      lockAtLeastFor = "PT30S")
  public void cleanupArchivedFiles() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    fileGovernanceScheduler.cleanupArchivedFiles();
  }
}
