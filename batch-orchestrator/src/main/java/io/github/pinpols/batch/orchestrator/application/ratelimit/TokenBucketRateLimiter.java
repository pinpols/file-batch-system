package io.github.pinpols.batch.orchestrator.application.ratelimit;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 集群级限流器：基于 Redis 固定窗口计数，非真正的 token bucket 算法——类名沿用历史命名。
 *
 * <p>算法：{@code windowStart = floor(now / 60s) * 60s}，每个 (tenant, action, windowStart) 为独立计数器， 每次
 * {@code INCR}；当计数 ≤ {@code maxPerMinute} 放行，超额拒绝；窗口 TTL 60s 自动滚动。
 *
 * <p>固定窗口的已知副作用：相邻窗口边界处可能短暂出现 2× 配额突发，业务上可接受（限流本就是粗粒度保护）。
 *
 * <p><b>C-2.4 c · 时钟回拨保护</b>：若检测到 {@code nowEpochMillis} 比上次调用早超过 {@link
 * #CLOCK_ROLLBACK_TOLERANCE_MILLIS}，记 WARN 并直接拒绝本次请求——回拨可能让 已过期的老窗口 key
 * 被重新激活叠加计数，导致限流失效。宁可短暂丢请求，也不让配额被击穿。
 */
@Slf4j
@Component
public class TokenBucketRateLimiter {

  private static final Duration WINDOW_TTL = Duration.ofSeconds(60);

  // C-2.4 c: 回拨 <100ms 视为系统抖动放行；≥100ms 视为真回拨，拒绝且记 WARN
  private static final long CLOCK_ROLLBACK_TOLERANCE_MILLIS = 100L;

  private final OrchestratorRedisSupport redis;
  // C-2.4 c: 记录最近一次 tryConsume 的 wall clock，用于回拨检测
  private final AtomicLong lastSeenMillis = new AtomicLong(0L);

  public TokenBucketRateLimiter(OrchestratorRedisSupport redis) {
    this.redis = redis;
  }

  public boolean tryConsume(
      String tenantId, String action, long maxPerMinute, long nowEpochMillis) {
    if (maxPerMinute <= 0) {
      return true;
    }
    if (!Texts.hasText(tenantId) || !Texts.hasText(action)) {
      return true;
    }
    if (isClockRollback(nowEpochMillis)) {
      log.warn(
          "clock rollback detected in rate limiter: tenantId={}, action={}, now={}, lastSeen={}"
              + " — rejecting this call to avoid double-counting against stale windows",
          tenantId,
          action,
          nowEpochMillis,
          lastSeenMillis.get());
      return false;
    }
    long windowStartEpochSecond = Math.floorDiv(nowEpochMillis, 60_000L) * 60L;
    Long current =
        redis.incrementWithinWindow(tenantId, action, windowStartEpochSecond, WINDOW_TTL);
    return current == null || current <= maxPerMinute;
  }

  /**
   * 用 CAS 单调推进 lastSeenMillis；若传入 now 比 last 早超过容忍阈值即视为回拨。 CAS 更新不成功时不重试——说明另一线程已经把时间推到更晚，我们直接以当次
   * now 判断即可。
   */
  private boolean isClockRollback(long nowEpochMillis) {
    while (true) {
      long last = lastSeenMillis.get();
      if (nowEpochMillis >= last) {
        if (lastSeenMillis.compareAndSet(last, nowEpochMillis)) {
          return false;
        }
        // CAS 输了就让下次调用重新判定
        continue;
      }
      return (last - nowEpochMillis) >= CLOCK_ROLLBACK_TOLERANCE_MILLIS;
    }
  }
}
