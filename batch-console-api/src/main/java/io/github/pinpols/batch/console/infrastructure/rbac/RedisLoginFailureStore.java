package io.github.pinpols.batch.console.infrastructure.rbac;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.rbac.support.LoginFailureStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis Sorted Set 版登录失败窗口存储。 */
@Component
@RequiredArgsConstructor
public class RedisLoginFailureStore implements LoginFailureStore {

  /** ZADD member + 清窗口外 + EXPIRE,返回窗口内计数。 */
  private static final DefaultRedisScript<Long> RECORD_SCRIPT;

  /** 清窗口外 + ZCARD 读计数。 */
  private static final DefaultRedisScript<Long> COUNT_SCRIPT;

  static {
    RECORD_SCRIPT = new DefaultRedisScript<>();
    RECORD_SCRIPT.setResultType(Long.class);
    RECORD_SCRIPT.setScriptText("""
        redis.call('ZADD', KEYS[1], ARGV[1], ARGV[3])
        redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[2])
        redis.call('EXPIRE', KEYS[1], ARGV[4])
        return redis.call('ZCARD', KEYS[1])
        """.stripTrailing());

    COUNT_SCRIPT = new DefaultRedisScript<>();
    COUNT_SCRIPT.setResultType(Long.class);
    COUNT_SCRIPT.setScriptText("""
        redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
        return redis.call('ZCARD', KEYS[1])
        """.stripTrailing());
  }

  private final StringRedisTemplate redisTemplate;

  @Override
  public long recordFailure(
      String key, long now, long windowStart, String member, long ttlSeconds) {
    Long count = redisTemplate.execute(
        RECORD_SCRIPT,
        List.of(key),
        String.valueOf(now),
        String.valueOf(windowStart),
        member,
        String.valueOf(ttlSeconds));
    return EmptyChecks.isNull(count) ? 0L : count;
  }

  @Override
  public long count(String key, long windowStart) {
    Long count = redisTemplate.execute(COUNT_SCRIPT, List.of(key), String.valueOf(windowStart));
    return EmptyChecks.isNull(count) ? 0L : count;
  }

  @Override
  public void delete(String key) {
    redisTemplate.delete(key);
  }
}
