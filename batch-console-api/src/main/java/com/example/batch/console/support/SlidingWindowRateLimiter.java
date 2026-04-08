package com.example.batch.console.support;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis Sorted Set 的滑动时间窗口限流器。
 *
 * <p>使用 Lua 脚本保证"计数检查 + 写入"的原子性，消除并发竞态。
 * 每个限流 key 对应一个 Sorted Set：score = 请求时间戳（ms），member = 唯一标识。
 *
 * <p>算法：
 * <ol>
 *   <li>移除窗口外的旧成员（{@code ZREMRANGEBYSCORE key 0 now-window}）</li>
 *   <li>计数当前窗口内成员数（{@code ZCARD key}）</li>
 *   <li>未超限则写入本次请求记录（{@code ZADD key now member}）并刷新 TTL</li>
 *   <li>返回 1（允许）或 0（超限）</li>
 * </ol>
 *
 * <p>相比 INCR+EXPIRE（固定窗口），滑动窗口不存在边界突破问题：
 * 任意时刻往回看 60 秒，窗口内永远最多 {@code limit} 次。
 */
@Component
@RequiredArgsConstructor
public class SlidingWindowRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    /**
     * Lua 脚本：原子执行滑动窗口计数 + 条件写入。
     * KEYS[1] = rate limit key
     * ARGV[1] = now (ms)
     * ARGV[2] = window start (ms) = now - windowMillis
     * ARGV[3] = limit
     * ARGV[4] = unique member
     * ARGV[5] = TTL seconds
     * 返回 1 表示允许，0 表示超限。
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
        RATE_LIMIT_SCRIPT.setScriptText(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[2]) " +
            "local count = redis.call('ZCARD', KEYS[1]) " +
            "if count < tonumber(ARGV[3]) then " +
            "  redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4]) " +
            "  redis.call('EXPIRE', KEYS[1], ARGV[5]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end"
        );
    }

    private final StringRedisTemplate redisTemplate;

    /**
     * 尝试为指定 key 消费一个令牌。
     *
     * @param key   限流 key（如 "login:ip:1.2.3.4" 或 "sensitive:user:alice"）
     * @param limit 滑动窗口（1 分钟）内最大请求次数
     * @return {@code true} 表示允许通过，{@code false} 表示超限
     */
    public boolean tryAcquire(String key, int limit) {
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MILLIS;
        long ttlSeconds = (WINDOW_MILLIS / 1000) + 1;
        String member = UUID.randomUUID().toString();

        Long result = redisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                List.of("rate_limit:" + key),
                String.valueOf(now),
                String.valueOf(windowStart),
                String.valueOf(limit),
                member,
                String.valueOf(ttlSeconds)
        );
        return Long.valueOf(1L).equals(result);
    }
}
