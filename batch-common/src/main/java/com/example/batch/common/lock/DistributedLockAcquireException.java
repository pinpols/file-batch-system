package com.example.batch.common.lock;

/** 抢分布式锁失败时抛出。调用方 catch 回退为「资源忙,稍后重试」业务语义。 */
public class DistributedLockAcquireException extends RuntimeException {

  private final String lockKey;

  public DistributedLockAcquireException(String lockKey) {
    super("failed to acquire distributed lock: " + lockKey);
    this.lockKey = lockKey;
  }

  public String getLockKey() {
    return lockKey;
  }
}
