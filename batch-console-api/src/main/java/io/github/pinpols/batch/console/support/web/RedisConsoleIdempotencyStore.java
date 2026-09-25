package io.github.pinpols.batch.console.support.web;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 版控制台写请求幂等存储。 */
@Component
@RequiredArgsConstructor
public class RedisConsoleIdempotencyStore implements ConsoleIdempotencyStore {

  private final StringRedisTemplate redisTemplate;

  @Override
  public String get(String key) {
    return redisTemplate.opsForValue().get(key);
  }

  @Override
  public Boolean setIfAbsent(String key, String value, Duration ttl) {
    return redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
  }

  @Override
  public void set(String key, String value, Duration ttl) {
    redisTemplate.opsForValue().set(key, value, ttl);
  }

  @Override
  public void delete(String key) {
    redisTemplate.delete(key);
  }
}
