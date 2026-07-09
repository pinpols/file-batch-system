package io.github.pinpols.batch.orchestrator.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Bucket4j 分布式令牌桶装配：为 {@code TokenBucketRateLimiter} 提供 Redis 后端的 {@link LettuceBasedProxyManager}。
 *
 * <p><b>为何复用 Spring 的 Lettuce 原生客户端</b>：直接从自动装配的 {@link LettuceConnectionFactory} 取 {@link
 * AbstractRedisClient}，与 {@code StringRedisTemplate} 共用同一套连接配置（host/port/auth/ssl/超时）， 零配置漂移；用独立的
 * {@code String→byte[]} 编解码器另开一条连接（bucket4j 值为二进制快照）， key 保持可读的 {@code ratelimit:{tenant}:{action}}
 * UTF-8 字符串。
 *
 * <p><b>多副本共享配额</b>：所有 orchestrator 副本连同一 Redis，bucket4j 以 CAS Lua 在 Redis 侧原子读改写桶状态， 各副本对同一 key
 * 竞争同一份令牌，故多副本共享配额。
 *
 * <p><b>时钟源与回拨安全</b>：bucket4j 分布式 CAS proxy manager 的 refill 时间源是<b>客户端墙钟</b>（{@code
 * TimeMeter.SYSTEM_MILLISECONDS}，Lua 不调 Redis TIME）。移除旧自研时钟回拨保护仍安全，因为 {@code BucketState} 的 refill
 * 有<b>单调钳制</b>（{@code currentTimeNanos <= lastRefillTimeNanos} 时直接 return，回拨既不补令牌也不回挪
 * lastRefillTime）：单机回拨最多导致少补令牌 = 更严限流（安全方向），永远不会像旧固定窗口那样复活老窗口 key 叠加计数击穿配额。
 *
 * <p><b>请求超时</b>：给 proxy manager 配 {@code requestTimeout}，避免 Redis 故障时命令阻塞到 Lettuce
 * 默认命令超时（~60s）——热路径 claim/report（12000/min）会大量线程阻塞削弱 fail-open。设 500ms：既显著大于健康时限流判定的 Redis
 * RTT（sub-ms，足够区分健康/故障），又足够小以防线程饥饿。为何不是 2s：Redis 慢故障（TCP 接受不响应）时每次 tryConsume 阻塞至多 requestTimeout 才
 * fail-open；claim+report 合计约 200 req/s、Tomcat 默认 200 线程，若每请求平均持有 2s → 需求并发 400 超过池容量 200 →
 * orchestrator 所有 HTTP 端点因线程池耗尽不可用，fail-open 的善意被线程饥饿反噬。降到 500ms 把最坏并发压回约 100，小于池容量。
 *
 * <p><b>过期策略</b>：{@code basedOnTimeForRefillingBucketUpToMax(1min)}——空闲桶在“回满到容量所需时间”后过期， 既回收闲置
 * key，又让阈值配置变更在桶过期后自然生效。
 */
@Slf4j
@Configuration
public class Bucket4jRateLimitConfig {

  /** 每分钟配额语义：桶容量与 refill 均以 1 分钟为周期。 */
  private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

  /**
   * 限流判定请求超时：Redis 故障时快速抛错 fail-open，不阻塞热路径线程到 Lettuce 默认命令超时（~60s）。取 500ms 而非 2s， 防高并发下热路径线程被慢故障的
   * Redis 长时间挂起而耗尽 Tomcat 线程池（见类注释「请求超时」）。
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(500);

  /**
   * bucket4j 专用连接：与 Spring 共享底层 {@link RedisClient}，但使用 {@code String}(key)/{@code byte[]}(value)
   * 编解码器。声明 destroyMethod=close，随容器关闭释放。
   */
  @Bean(destroyMethod = "close")
  public StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(
      RedisConnectionFactory redisConnectionFactory) {
    if (!(redisConnectionFactory instanceof LettuceConnectionFactory lettuceConnectionFactory)) {
      throw new IllegalStateException(
          "rate limiter requires a LettuceConnectionFactory but found: "
              + redisConnectionFactory.getClass().getName());
    }
    AbstractRedisClient nativeClient = lettuceConnectionFactory.getNativeClient();
    if (!(nativeClient instanceof RedisClient redisClient)) {
      throw new IllegalStateException(
          "rate limiter requires a standalone Lettuce RedisClient but found: "
              + (nativeClient == null ? "null" : nativeClient.getClass().getName()));
    }
    log.info("initializing bucket4j rate-limit connection over shared lettuce RedisClient");
    return redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
  }

  @Bean
  public LettuceBasedProxyManager<String> rateLimitProxyManager(
      StatefulRedisConnection<String, byte[]> rateLimitRedisConnection) {
    return Bucket4jLettuce.casBasedBuilder(rateLimitRedisConnection)
        .requestTimeout(REQUEST_TIMEOUT)
        .expirationAfterWrite(
            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(REFILL_PERIOD))
        .build();
  }
}
