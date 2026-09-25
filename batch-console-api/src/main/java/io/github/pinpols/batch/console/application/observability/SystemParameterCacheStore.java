package io.github.pinpols.batch.console.application.observability;

import java.time.Duration;

/** 系统参数缓存端口。 */
public interface SystemParameterCacheStore {

  String get(String key);

  void put(String key, String value, Duration ttl);

  void evict(String key);
}
