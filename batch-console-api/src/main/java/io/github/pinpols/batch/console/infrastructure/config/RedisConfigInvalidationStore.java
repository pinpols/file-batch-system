package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.config.ConfigCacheInvalidationEvent;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.config.ConfigInvalidationStore;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.support.cache.RedisKeyUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 版配置缓存失效端口实现。 */
@Component
@RequiredArgsConstructor
public class RedisConfigInvalidationStore implements ConfigInvalidationStore {

  private final StringRedisTemplate redisTemplate;

  @Override
  public boolean delete(String key) {
    Boolean deleted = redisTemplate.delete(key);
    return Boolean.TRUE.equals(deleted);
  }

  @Override
  public long scanAndDelete(String pattern, int batchSize) {
    return RedisKeyUtils.scanAndDeleteOrThrow(redisTemplate, pattern, batchSize);
  }

  @Override
  public Long nextGlobalRevision() {
    return redisTemplate.opsForValue().increment(BatchRedisKeys.configInvalidationGlobalRevision());
  }

  @Override
  public Long nextKeyRevision(String tenantId, String type, String code) {
    return redisTemplate
        .opsForValue()
        .increment(BatchRedisKeys.configInvalidationKeyRevision(tenantId, type, code));
  }

  @Override
  public void publish(ConfigCacheInvalidationEvent event) {
    redisTemplate.convertAndSend(
        ConsoleConfigCacheInvalidationService.INVALIDATION_CHANNEL, JsonUtils.toJson(event));
  }
}
