package io.github.pinpols.batch.console.infrastructure.observability;

import io.github.pinpols.batch.console.application.observability.SseTicketStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 版 SSE 一次性 ticket 存储。 */
@Component
@RequiredArgsConstructor
public class RedisSseTicketStore implements SseTicketStore {

  private static final String KEY_PREFIX = "console:sse:ticket:";

  private final StringRedisTemplate redisTemplate;

  @Override
  public void save(String ticket, String value, Duration ttl) {
    redisTemplate.opsForValue().set(KEY_PREFIX + ticket, value, ttl);
  }

  @Override
  public String consume(String ticket) {
    return redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + ticket);
  }
}
