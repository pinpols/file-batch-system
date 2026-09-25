package io.github.pinpols.batch.console.domain.workflow.application;

import java.time.Duration;

/** Workflow 设计器编辑锁存储端口。 */
public interface DesignLockStore {

  Boolean acquire(String key, String payload, Duration ttl);

  Long release(String key, String userId);

  Long renew(String key, String userId, String payload, long ttlMillis);

  String get(String key);
}
