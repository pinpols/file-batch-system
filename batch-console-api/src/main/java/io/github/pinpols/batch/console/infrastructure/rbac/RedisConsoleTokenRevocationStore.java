package io.github.pinpols.batch.console.infrastructure.rbac;

import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTokenRevocationStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 版 Console JWT 撤销名单。 */
@Component
@RequiredArgsConstructor
public class RedisConsoleTokenRevocationStore implements ConsoleTokenRevocationStore {

  private static final String REVOKED_KEY_PREFIX = "console:revoked:jti:";

  private final StringRedisTemplate redisTemplate;

  @Override
  public boolean isRevoked(String jti) {
    Boolean revoked = redisTemplate.hasKey(REVOKED_KEY_PREFIX + jti);
    return Boolean.TRUE.equals(revoked);
  }

  @Override
  public void revoke(String jti, Duration ttl) {
    redisTemplate.opsForValue().set(REVOKED_KEY_PREFIX + jti, "1", ttl);
  }
}
