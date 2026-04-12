package com.example.batch.orchestrator.application.ratelimit;

import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 基于 Redis 固定窗口的集群级限流器。 */
@Component
@RequiredArgsConstructor
public class TokenBucketRateLimiter {

  private static final Duration WINDOW_TTL = Duration.ofSeconds(60);

  private final OrchestratorRedisSupport redis;

  public boolean tryConsume(
      String tenantId, String action, long maxPerMinute, long nowEpochMillis) {
    if (maxPerMinute <= 0) {
      return true;
    }
    if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(action)) {
      return true;
    }
    long windowStartEpochSecond = Math.floorDiv(nowEpochMillis, 60_000L) * 60L;
    Long current =
        redis.incrementWithinWindow(tenantId, action, windowStartEpochSecond, WINDOW_TTL);
    return current == null || current <= maxPerMinute;
  }
}
