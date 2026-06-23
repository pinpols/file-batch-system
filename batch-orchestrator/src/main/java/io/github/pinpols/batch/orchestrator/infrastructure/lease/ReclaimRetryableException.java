package io.github.pinpols.batch.orchestrator.infrastructure.lease;

/**
 * partition reclaim 单元事务可重试型失败：第二步 CAS（task resetForRetry）冲突时抛出， 让 Spring 事务回滚已 reset 的
 * partition，避免半成功死态；下一轮 reclaim 会从同一过期 lease 行重试。
 */
public class ReclaimRetryableException extends RuntimeException {

  public ReclaimRetryableException(String message) {
    super(message);
  }
}
