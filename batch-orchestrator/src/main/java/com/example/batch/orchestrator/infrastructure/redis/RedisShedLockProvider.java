package com.example.batch.orchestrator.infrastructure.redis;

import com.example.batch.common.redis.BatchRedisKeys;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

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
    Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
    if (!Boolean.TRUE.equals(acquired)) {
      return Optional.empty();
    }
    return Optional.of(
        () ->
            redisTemplate.execute(
                new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class), List.of(key), token));
  }
}
