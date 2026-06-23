package io.github.pinpols.batch.orchestrator.application.service.governance;

/**
 * 死信重放时发现 {@code JOB_PARTITION} / {@code job_instance} 源行已不存在（常见于运维清理残留数据）。
 *
 * <p>抛出此类时事务不应回滚已提交的 {@code markGiveUp} 更新（见 {@link
 * org.springframework.transaction.annotation.Transactional#noRollbackFor}）。
 */
public final class DeadLetterOrphanSourceException extends RuntimeException {

  public DeadLetterOrphanSourceException(String message) {
    super(message);
  }
}
