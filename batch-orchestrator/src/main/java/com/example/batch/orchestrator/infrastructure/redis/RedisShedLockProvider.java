package com.example.batch.orchestrator.infrastructure.redis;

import com.example.batch.common.redis.BatchRedisKeys;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 基于 Redis SET NX 的 ShedLock {@link LockProvider} 实现：用随机 token 作为锁值，
 * 解锁时通过 Lua 脚本 CAS 比较 token 再删除，防止误释放其他节点持有的锁。
 *
 * <p>锁键格式由 {@link com.example.batch.common.redis.BatchRedisKeys#shedLock} 生成，
 * 包含 {@code environment} 前缀以隔离不同环境（dev/staging/prod）。
 */
@Slf4j
@RequiredArgsConstructor
public class RedisShedLockProvider implements LockProvider {

  private static final String UNLOCK_SCRIPT =
      """
      if redis.call('GET', KEYS[1]) == ARGV[1] then
        return redis.call('DEL', KEYS[1])
      end
      return 0
      """;

  private final StringRedisTemplate redisTemplate;
  private final String environment;

  @Override
  public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
    String key = BatchRedisKeys.shedLock(environment, lockConfiguration.getName());
    String token = UUID.randomUUID().toString();
    Duration ttl = Duration.between(Instant.now(), lockConfiguration.getLockAtMostUntil());
    if (ttl.isNegative() || ttl.isZero()) {
      ttl = Duration.ofSeconds(1);
    }
    Boolean acquired;
    try {
      acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
    } catch (DataAccessException redisError) {
      // Redis 瞬时故障（超时、连接拒绝、节点切主）：当作"未拿到锁"处理，
      // 下一 tick 自然重试，避免异常冒泡到 Spring scheduler 打 ERROR。
      log.warn(
          "shed-lock acquire failed, treating as not-held: lock={}, reason={}",
          lockConfiguration.getName(),
          redisError.getMostSpecificCause() == null
              ? redisError.getClass().getSimpleName()
              : redisError.getMostSpecificCause().getMessage());
      return Optional.empty();
    }
    if (!Boolean.TRUE.equals(acquired)) {
      return Optional.empty();
    }
    return Optional.of(
        () -> {
          try {
            redisTemplate.execute(
                new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class), List.of(key), token);
          } catch (DataAccessException redisError) {
            // 解锁失败：key 到期自然释放，这里只记一条 WARN 不上抛。
            log.warn(
                "shed-lock release failed (ttl will expire key): lock={}, reason={}",
                lockConfiguration.getName(),
                redisError.getMostSpecificCause() == null
                    ? redisError.getClass().getSimpleName()
                    : redisError.getMostSpecificCause().getMessage());
          }
        });
  }
}
