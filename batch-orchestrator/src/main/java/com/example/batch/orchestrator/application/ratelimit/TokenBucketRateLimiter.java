package com.example.batch.orchestrator.application.ratelimit;

import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 集群级限流器：基于 Redis 固定窗口计数，非真正的 token bucket 算法——类名沿用历史命名。
 *
 * <p>算法：{@code windowStart = floor(now / 60s) * 60s}，每个 (tenant, action, windowStart) 为独立计数器，
 * 每次 {@code INCR}；当计数 ≤ {@code maxPerMinute} 放行，超额拒绝；窗口 TTL 60s 自动滚动。
 *
 * <p>固定窗口的已知副作用：相邻窗口边界处可能短暂出现 2× 配额突发，业务上可接受（限流本就是粗粒度保护）。
 */
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
