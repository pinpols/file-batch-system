package io.github.pinpols.batch.orchestrator.application.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.TimeoutException;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.utils.Texts;
import io.lettuce.core.RedisException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 集群级令牌桶限流器：基于 Bucket4j + Redis（Lettuce）分布式后端，是名副其实的 token bucket。
 *
 * <p><b>语义</b>：每 (tenant, action) 一个桶，容量 = 该 action 的每分钟阈值，refill = greedy（在 1 分钟内平滑、 按比例持续补充
 * {@code maxPerMinute} 个令牌）。{@code tryConsume(1)} 成功放行、失败拒绝。
 *
 * <p><b>相较旧固定窗口的改进</b>：旧实现按 {@code floor(now/60s)} 分窗独立计数，相邻窗口边界可短暂放行 2× 配额（突发缺陷）。令牌桶的瞬时突发上限就是桶容量 =
 * {@code maxPerMinute}（< 旧的 2×），且 greedy 补充令令牌 平滑回填而非整分钟一次性投放，从而消除窗口边界翻倍突发。稳态速率仍为 {@code
 * maxPerMinute}/min。
 *
 * <p><b>时钟回拨</b>：不再需要旧的自研 CAS 回拨保护。bucket4j 分布式 CAS proxy manager 的 refill 时间源其实是<b>客户端墙钟</b>
 * （{@code TimeMeter.SYSTEM_MILLISECONDS}，Lua 不调 Redis TIME），但移除保护仍安全：{@code BucketState} 的 refill
 * 有<b>单调钳制</b> （{@code currentTimeNanos <= lastRefillTimeNanos} 时直接 return，回拨既不补令牌也不回挪
 * lastRefillTime），故单机回拨最多导致少补令牌 = 更严限流（安全方向），永远不会像旧固定窗口那样复活老窗口 key 叠加计数击穿配额。
 *
 * <p><b>降级方向</b>：Redis 不可达时 fail-open（放行），与旧实现 {@code
 * OrchestratorRedisSupport.incrementWithinWindow} 返回 null 即放行的语义一致。
 */
@Slf4j
@Component
public class TokenBucketRateLimiter {

  private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

  private final LettuceBasedProxyManager<String> proxyManager;

  // 按 maxPerMinute 缓存 BucketConfiguration supplier，避免每次 tryConsume 都新建（阈值集合很小，见
  // RateLimitProperties）。
  private final Map<Long, Supplier<BucketConfiguration>> configSuppliers =
      new ConcurrentHashMap<>();

  public TokenBucketRateLimiter(LettuceBasedProxyManager<String> proxyManager) {
    this.proxyManager = proxyManager;
  }

  public boolean tryConsume(String tenantId, String action, long maxPerMinute) {
    if (maxPerMinute <= 0) {
      return true;
    }
    if (!Texts.hasText(tenantId) || !Texts.hasText(action)) {
      return true;
    }
    String key = BatchRedisKeys.rateLimitBucket(tenantId, action);
    try {
      return proxyManager.getProxy(key, configSupplierFor(maxPerMinute)).tryConsume(1);
    } catch (RedisException | TimeoutException ex) {
      // fail-open：放行，与旧固定窗口实现同方向。宁可短暂不限流，也不因 Redis 抖动整体 5xx。
      // 坑：Redis 命令级故障抛 io.lettuce.core.RedisException；但 proxy manager 配了 requestTimeout 后，
      //     超时抛的是 io.github.bucket4j.TimeoutException（不是 RedisException）——必须一并兜住，
      //     否则超时会逃逸 catch → fail-closed(500)，静默反转降级方向。
      log.warn(
          "Redis rate-limit unavailable; fail-open: tenantId={}, action={}, cause={}",
          tenantId,
          action,
          ex.getMessage());
      log.debug("Redis rate-limit failure: key={}", key, ex);
      return true;
    }
  }

  private Supplier<BucketConfiguration> configSupplierFor(long maxPerMinute) {
    return configSuppliers.computeIfAbsent(maxPerMinute, this::buildConfigSupplier);
  }

  private Supplier<BucketConfiguration> buildConfigSupplier(long maxPerMinute) {
    BucketConfiguration configuration =
        BucketConfiguration.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(maxPerMinute)
                    .refillGreedy(maxPerMinute, REFILL_PERIOD)
                    .build())
            .build();
    return () -> configuration;
  }
}
