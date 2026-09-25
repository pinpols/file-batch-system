package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.console.domain.workflow.application.DesignLockStore;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Redis 版 Workflow 设计器编辑锁存储。 */
@Component
@RequiredArgsConstructor
public class RedisDesignLockStore implements DesignLockStore {

  /**
   * 原子释放:GET → 校验 lockedBy == 调用者 → DEL,全程在 Redis 单线程内执行。
   * 返回 0=无锁 / 1=已删 / -1=非持锁人。
   */
  private static final RedisScript<Long> RELEASE_SCRIPT =
      new DefaultRedisScript<>("""
          local v = redis.call('GET', KEYS[1])
          if not v then return 0 end
          if cjson.decode(v)['lockedBy'] == ARGV[1] then
            return redis.call('DEL', KEYS[1])
          else
            return -1
          end
          """.stripTrailing(), Long.class);

  /** 原子续期:GET → 校验 lockedBy == 调用者 → SET 新 payload + TTL。 */
  private static final RedisScript<Long> RENEW_SCRIPT =
      new DefaultRedisScript<>("""
          local v = redis.call('GET', KEYS[1])
          if not v then return 0 end
          if cjson.decode(v)['lockedBy'] == ARGV[1] then
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
          else
            return -1
          end
          """.stripTrailing(), Long.class);

  private final StringRedisTemplate redisTemplate;

  @Override
  public Boolean acquire(String key, String payload, Duration ttl) {
    return redisTemplate.opsForValue().setIfAbsent(key, payload, ttl);
  }

  @Override
  public Long release(String key, String userId) {
    return redisTemplate.execute(RELEASE_SCRIPT, List.of(key), userId);
  }

  @Override
  public Long renew(String key, String userId, String payload, long ttlMillis) {
    return redisTemplate.execute(
        RENEW_SCRIPT, List.of(key), userId, payload, String.valueOf(ttlMillis));
  }

  @Override
  public String get(String key) {
    return redisTemplate.opsForValue().get(key);
  }
}
