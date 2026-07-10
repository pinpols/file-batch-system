package io.github.pinpols.batch.orchestrator.application.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.pinpols.batch.orchestrator.config.RateLimitProperties;
import io.github.pinpols.batch.testing.TestContainerImages;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 真 Redis 短路熔断端到端测试：验证 #782 遗留的"慢故障下每请求叠 500ms"被熔断消除。
 *
 * <p>场景：真 Redis 容器建连后停机 → 连续几次 {@code tryConsume} 各阻塞至多 requestTimeout(500ms) 才 fail-open、并累积失败 →
 * 熔断 OPEN → <b>后续请求不再发 Redis 命令，near-instant fail-open</b>（elapsed 远小于 500ms），证明短路省掉了阻塞。
 */
class RedisRateLimitCircuitBreakerIntegrationTest {

  private static RateLimitProperties.CircuitBreaker config() {
    RateLimitProperties.CircuitBreaker config = new RateLimitProperties.CircuitBreaker();
    config.setEnabled(true);
    config.setConsecutiveFailures(3);
    config.setOpenWindowMillis(10_000L); // 保持 OPEN,专测短路省阻塞
    config.setHalfOpenProbes(1);
    return config;
  }

  @Test
  @DisplayName("真 Redis 停机 → 连续失败开熔断 → 后续请求短路 near-instant fail-open(不再阻塞 500ms)")
  void sustainedRealRedisFailureOpensCircuitAndStopsStalling() {
    @SuppressWarnings("resource")
    GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse(TestContainerImages.VALKEY))
            .withExposedPorts(6379);
    redis.start();

    RedisClient client = RedisClient.create();
    // autoReconnect(false):Redis 停机后命令立即失败,不无限重连;命令超时交给 bucket4j requestTimeout(500ms) 兜底。
    client.setOptions(
        ClientOptions.builder()
            .autoReconnect(false)
            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build())
            .build());
    RedisURI uri =
        RedisURI.builder()
            .withHost(redis.getHost())
            .withPort(redis.getMappedPort(6379))
            .withTimeout(Duration.ofSeconds(2))
            .build();
    try (StatefulRedisConnection<String, byte[]> connection =
        client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE), uri)) {
      // requestTimeout 与生产 Bucket4jRateLimitConfig.REQUEST_TIMEOUT 对齐(500ms)。
      LettuceBasedProxyManager<String> proxyManager =
          Bucket4jLettuce.casBasedBuilder(connection)
              .requestTimeout(Duration.ofMillis(500))
              .expirationAfterWrite(
                  ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                      Duration.ofMinutes(1)))
              .build();
      SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
      RedisRateLimitCircuitBreaker circuitBreaker =
          new RedisRateLimitCircuitBreaker(
              config(), CircuitBreakerRegistry.ofDefaults(), meterRegistry);
      TokenBucketRateLimiter limiter =
          new TokenBucketRateLimiter(proxyManager, meterRegistry, circuitBreaker);

      // sanity:健康时正常限流
      assertThat(limiter.tryConsume("t-cb", "LAUNCH", 100)).isTrue();

      // Redis 停机 → 后续命令不可达
      redis.stop();

      // 连续 3 次失败填满窗口 → 熔断 OPEN。每次真发命令、阻塞至多 requestTimeout。
      for (int i = 0; i < 3; i++) {
        assertThat(limiter.tryConsume("t-cb", "LAUNCH", 100))
            .as("failing call %d must fail-open", i + 1)
            .isTrue();
      }
      assertThat(
              meterRegistry.get(RedisRateLimitCircuitBreaker.METRIC_CIRCUIT_OPEN).gauge().value())
          .as("circuit should be OPEN after 3 sustained real-Redis failures")
          .isEqualTo(1.0);

      // 熔断已 OPEN:下一次请求应被短路,near-instant 返回,不再阻塞到 requestTimeout(500ms)。
      long startNanos = System.nanoTime();
      boolean allowed = limiter.tryConsume("t-cb", "LAUNCH", 100);
      long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

      assertThat(allowed).as("open circuit must fail-open").isTrue();
      assertThat(elapsedMillis)
          .as("short-circuit must skip Redis entirely, far below requestTimeout(500ms)")
          .isLessThan(100L);
      assertThat(
              meterRegistry
                  .get(TokenBucketRateLimiter.METRIC_FAILOPEN)
                  .tag("reason", TokenBucketRateLimiter.FAILOPEN_REASON_CIRCUIT_OPEN)
                  .counter()
                  .count())
          .as("short-circuited request must count reason=circuit_open")
          .isGreaterThanOrEqualTo(1.0);
    } finally {
      client.shutdown();
      redis.stop();
    }
  }
}
