package com.example.batch.orchestrator.application.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于令牌桶的内存限流器（单实例有效）。
 *
 * <p>配置语义使用“每分钟最大 X 次”，实现为令牌桶：
 * 令牌以固定速率补充，消耗 1 个令牌表示通过 1 次请求。</p>
 */
public class TokenBucketRateLimiter {

    private static class Bucket {
        private final long capacity;
        private final long refillIntervalMillis;
        private double tokens;
        private long lastRefillEpochMillis;

        private Bucket(long capacity, long refillIntervalMillis) {
            this.capacity = capacity;
            this.refillIntervalMillis = refillIntervalMillis;
            this.tokens = capacity;
            this.lastRefillEpochMillis = System.currentTimeMillis();
        }

        private boolean tryConsume(long nowEpochMillis) {
            // 简单同步保证同一桶的原子性；桶的数量通常按 tenant/action 分散。
            synchronized (this) {
                refill(nowEpochMillis);
                if (tokens < 1D) {
                    return false;
                }
                tokens -= 1D;
                return true;
            }
        }

        private void refill(long nowEpochMillis) {
            if (nowEpochMillis <= lastRefillEpochMillis) {
                return;
            }
            long elapsedMillis = nowEpochMillis - lastRefillEpochMillis;
            if (elapsedMillis <= 0) {
                return;
            }
            // 按固定区间补充 tokens，并在上限处封顶。
            double refillTokens = (double) elapsedMillis * (double) capacity / (double) refillIntervalMillis;
            tokens = Math.min((double) capacity, tokens + refillTokens);
            lastRefillEpochMillis = nowEpochMillis;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 尝试消耗令牌。
     *
     * @param key 限流维度 key（例如 tenantId + action）
     * @param maxPerMinute 每分钟最大次数（<=0 表示放行）
     * @param nowEpochMillis 当前时间
     */
    public boolean tryConsume(String key, long maxPerMinute, long nowEpochMillis) {
        if (maxPerMinute <= 0) {
            return true;
        }
        if (key == null || key.isBlank()) {
            return true;
        }
        long capacity = maxPerMinute;
        long refillIntervalMillis = 60_000L;
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, refillIntervalMillis));
        // capacity 在 Bucket 创建后固定；如果你会动态变更阈值，需要改为带版本的 key。
        return bucket.tryConsume(nowEpochMillis);
    }
}

