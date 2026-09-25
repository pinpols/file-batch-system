package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.common.config.ConfigCacheInvalidationEvent;

/** 配置缓存失效存储与通知端口，封装 Redis key 删除、版本号和跨实例发布细节。 */
public interface ConfigInvalidationStore {

  boolean delete(String key);

  long scanAndDelete(String pattern, int batchSize);

  Long nextGlobalRevision();

  Long nextKeyRevision(String tenantId, String type, String code);

  void publish(ConfigCacheInvalidationEvent event);
}
