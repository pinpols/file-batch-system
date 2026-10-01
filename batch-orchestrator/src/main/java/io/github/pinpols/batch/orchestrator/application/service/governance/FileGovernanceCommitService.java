package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件治理的短事务提交边界。
 *
 * <p>对象存储探测可能受网络和远端服务影响，不能和数据库连接占用放在同一个事务中。这个组件只包住完成状态和审计写入，确保
 * 数据库内的两步仍然原子提交。
 */
@Service
@RequiredArgsConstructor
public class FileGovernanceCommitService {

  private final FileGovernanceRepository fileGovernanceRepository;

  /** 记录预签名成功审计，事务只覆盖数据库写入。 */
  @Transactional
  public void appendAudit(FileGovernanceRepository.FileAuditCommand auditCommand) {
    fileGovernanceRepository.appendAudit(auditCommand);
  }

  /**
   * 在对象存储探测完成后，用短事务提交到达状态和审计。
   *
   * <p>调用方应在进入本方法前完成对象存储探测；本方法不再访问对象存储。
   */
  @Transactional
  public void confirmArrival(
      String tenantId,
      Long fileId,
      long fileSizeBytes,
      Object metadata,
      FileGovernanceRepository.FileAuditCommand auditCommand) {
    fileGovernanceRepository.markFileArrivalConfirmed(tenantId, fileId, fileSizeBytes, metadata);
    fileGovernanceRepository.appendAudit(auditCommand);
  }
}
