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
 * <p><b>多副本共享配额</b>：所有 orchestrator 副本连同一 Redis，bucket4j 以 CAS Lua 在 Redis 侧原子推进桶状态， 令牌按 refill 速率在
 * Redis 服务端时间累积——不依赖任何单副本 wall-clock 窗口对齐，故天然免疫单机时钟回拨。
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
        .expirationAfterWrite(
            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(REFILL_PERIOD))
        .build();
  }
}
