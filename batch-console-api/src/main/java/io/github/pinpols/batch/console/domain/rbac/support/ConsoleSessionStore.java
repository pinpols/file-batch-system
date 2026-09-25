package io.github.pinpols.batch.console.domain.rbac.support;

import java.time.Duration;

/** 控制台会话版本存储端口。 */
public interface ConsoleSessionStore {

  Long increment(String key);

  void expire(String key, Duration ttl);

  String get(String key);

  void delete(String key);
}
