package io.github.pinpols.batch.orchestrator.infrastructure.file;

import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件治理——托管上传会话孤儿清理调度器（#440）。
 *
 * <p>默认每 1 小时触发一次，委托 {@link FileGovernanceScheduler#cleanupOrphanUploadSessions()} 清理
 * createUploadSession 创建后既未上传也未确认到达、且超过 TTL 的占位 file_record。 ShedLock 锁名 {@code
 * file_governance_upload_session_cleanup}，最长持锁 3 分钟，最短持锁 30 秒。 Orchestrator 优雅停机时跳过执行。
 */
@Component
@RequiredArgsConstructor
public class FileGovernanceUploadSessionCleanupScheduler {

  private final FileGovernanceScheduler fileGovernanceScheduler;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(
      fixedDelayString = "${batch.file-governance.upload-session.cleanup-interval-millis:3600000}")
  @SchedulerLock(
      name = "file_governance_upload_session_cleanup",
      lockAtMostFor = "PT3M",
      lockAtLeastFor = "PT30S")
  public void cleanupOrphanUploadSessions() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    fileGovernanceScheduler.cleanupOrphanUploadSessions();
  }
}
