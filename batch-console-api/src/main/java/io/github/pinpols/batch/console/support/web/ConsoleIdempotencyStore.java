package io.github.pinpols.batch.console.support.web;

import java.time.Duration;

/** 控制台写请求幂等存储端口。 */
public interface ConsoleIdempotencyStore {

  String get(String key);

  Boolean setIfAbsent(String key, String value, Duration ttl);

  void set(String key, String value, Duration ttl);

  void delete(String key);
}
