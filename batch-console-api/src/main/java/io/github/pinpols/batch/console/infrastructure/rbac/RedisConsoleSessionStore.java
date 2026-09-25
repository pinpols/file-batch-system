package io.github.pinpols.batch.console.infrastructure.rbac;

import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSessionStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 版控制台会话版本存储。 */
@Component
@RequiredArgsConstructor
public class RedisConsoleSessionStore implements ConsoleSessionStore {

  private final StringRedisTemplate redisTemplate;

  @Override
  public Long increment(String key) {
    return redisTemplate.opsForValue().increment(key);
  }

  @Override
  public void expire(String key, Duration ttl) {
    redisTemplate.expire(key, ttl);
  }

  @Override
  public String get(String key) {
    return redisTemplate.opsForValue().get(key);
  }

  @Override
  public void delete(String key) {
    redisTemplate.delete(key);
  }
}
