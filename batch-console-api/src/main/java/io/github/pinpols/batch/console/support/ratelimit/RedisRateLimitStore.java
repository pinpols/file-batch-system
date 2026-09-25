package io.github.pinpols.batch.console.support.ratelimit;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis Sorted Set 版限流窗口存储。 */
@Component
@RequiredArgsConstructor
public class RedisRateLimitStore implements RateLimitStore {

  private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

  static {
    RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
    RATE_LIMIT_SCRIPT.setResultType(Long.class);
    RATE_LIMIT_SCRIPT.setScriptText("""
        redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[2])
        local count = redis.call('ZCARD', KEYS[1])
        if count < tonumber(ARGV[3]) then
          redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
          redis.call('EXPIRE', KEYS[1], ARGV[5])
          return 1
        else
          return 0
        end
        """.stripTrailing());
  }

  private final StringRedisTemplate redisTemplate;

  @Override
  public boolean tryAcquire(
      String key, long now, long windowStart, int limit, String member, long ttlSeconds) {
    Long result = redisTemplate.execute(
        RATE_LIMIT_SCRIPT,
        List.of(key),
        String.valueOf(now),
        String.valueOf(windowStart),
        String.valueOf(limit),
        member,
        String.valueOf(ttlSeconds));
    return Long.valueOf(1L).equals(result);
  }
}
