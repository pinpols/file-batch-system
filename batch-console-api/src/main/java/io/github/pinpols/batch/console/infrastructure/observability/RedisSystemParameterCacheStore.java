package io.github.pinpols.batch.console.infrastructure.observability;

import io.github.pinpols.batch.console.application.observability.SystemParameterCacheStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 版系统参数缓存。 */
@Component
@RequiredArgsConstructor
public class RedisSystemParameterCacheStore implements SystemParameterCacheStore {

  private final StringRedisTemplate redisTemplate;

  @Override
  public String get(String key) {
    return redisTemplate.opsForValue().get(key);
  }

  @Override
  public void put(String key, String value, Duration ttl) {
    redisTemplate.opsForValue().set(key, value, ttl);
  }

  @Override
  public void evict(String key) {
    redisTemplate.delete(key);
  }
}
