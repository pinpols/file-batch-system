package io.github.pinpols.batch.console.support.web;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis 版控制台写请求幂等存储。 */
@Component
@RequiredArgsConstructor
public class RedisConsoleIdempotencyStore implements ConsoleIdempotencyStore {

  private static final DefaultRedisScript<Long> REPLACE = new DefaultRedisScript<>("""
      if redis.call('GET', KEYS[1]) == ARGV[1] then
        redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
        return 1
      end
      return 0
      """, Long.class);
  private static final DefaultRedisScript<Long> DELETE = new DefaultRedisScript<>("""
      if redis.call('GET', KEYS[1]) == ARGV[1] then
        return redis.call('DEL', KEYS[1])
      end
      return 0
      """, Long.class);

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
  public boolean compareAndSet(String key, String expected, String value, Duration ttl) {
    return Long.valueOf(1)
        .equals(redisTemplate.execute(
            REPLACE, List.of(key), expected, value, Long.toString(ttl.toMillis())));
  }

  @Override
  public boolean deleteIfValue(String key, String expected) {
    return Long.valueOf(1).equals(redisTemplate.execute(DELETE, List.of(key), expected));
  }
}
