package io.github.pinpols.batch.orchestrator.application.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.pinpols.batch.common.config.BatchClockConfig;
import io.github.pinpols.batch.orchestrator.config.Bucket4jRateLimitConfig;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 令牌桶限流器集成测试：用真实 Redis 容器验证 Bucket4j 分布式令牌桶语义。
 *
 * <p>覆盖：桶耗尽→拒绝、per-(tenant,action) 隔离、多“副本”共享同一 Redis 桶（配额跨副本聚合）、 greedy refill
 * 随时间恢复令牌、maxPerMinute<=0 放行。
 */
@SpringBootTest(
    classes = TokenBucketRateLimiterIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"batch.startup-self-check.enabled=false"})
class TokenBucketRateLimiterIntegrationTest extends AbstractIntegrationTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({BatchClockConfig.class, Bucket4jRateLimitConfig.class, TokenBucketRateLimiter.class})
  static class TestApplication {}

  @Autowired private TokenBucketRateLimiter limiter;

  private static String uniqueTenant() {
    return "t-tb-" + System.nanoTime();
  }

  @Test
  void consumesUpToCapacityThenRejects() {
    String tenant = uniqueTenant();
    long capacity = 5;

    for (int i = 0; i < capacity; i++) {
      assertThat(limiter.tryConsume(tenant, "LAUNCH", capacity))
          .as("token %d within capacity should be allowed", i + 1)
          .isTrue();
    }
    // 桶已空，且 refill 速率为 5/min（12s 补 1 个），测试瞬间内不会补回 → 拒绝。
    assertThat(limiter.tryConsume(tenant, "LAUNCH", capacity)).isFalse();
  }

  @Test
  void bucketsAreIsolatedPerTenantAndAction() {
    String tenantA = uniqueTenant();
    String tenantB = uniqueTenant();
    long capacity = 2;

    // 耗尽 tenantA / LAUNCH
    assertThat(limiter.tryConsume(tenantA, "LAUNCH", capacity)).isTrue();
    assertThat(limiter.tryConsume(tenantA, "LAUNCH", capacity)).isTrue();
    assertThat(limiter.tryConsume(tenantA, "LAUNCH", capacity)).isFalse();

    // 同租户不同 action 独立
    assertThat(limiter.tryConsume(tenantA, "TASK_CLAIM", capacity)).isTrue();
    // 不同租户同 action 独立
    assertThat(limiter.tryConsume(tenantB, "LAUNCH", capacity)).isTrue();
    assertThat(limiter.tryConsume(tenantB, "LAUNCH", capacity)).isTrue();
    assertThat(limiter.tryConsume(tenantB, "LAUNCH", capacity)).isFalse();
  }

  @Test
  void quotaIsSharedAcrossReplicasViaRedis() {
    String tenant = uniqueTenant();
    long capacity = 4;

    // 第二个“副本”：独立的 RedisClient/proxy manager 指向同一 Redis 同一 key。
    RedisClient secondClient = RedisClient.create(RedisURI.create(redisHost(), redisPort()));
    try (StatefulRedisConnection<String, byte[]> conn =
        secondClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))) {
      LettuceBasedProxyManager<String> secondProxyManager =
          Bucket4jLettuce.casBasedBuilder(conn)
              .expirationAfterWrite(
                  ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                      Duration.ofMinutes(1)))
              .build();
      TokenBucketRateLimiter replicaTwo =
          new TokenBucketRateLimiter(secondProxyManager, new SimpleMeterRegistry());

      // 副本1 消费 2 个
      assertThat(limiter.tryConsume(tenant, "LAUNCH", capacity)).isTrue();
      assertThat(limiter.tryConsume(tenant, "LAUNCH", capacity)).isTrue();
      // 副本2 看到共享余额，只剩 2 个
      assertThat(replicaTwo.tryConsume(tenant, "LAUNCH", capacity)).isTrue();
      assertThat(replicaTwo.tryConsume(tenant, "LAUNCH", capacity)).isTrue();
      // 两副本合计已达 capacity=4 → 任一副本再取都被拒
      assertThat(replicaTwo.tryConsume(tenant, "LAUNCH", capacity)).isFalse();
      assertThat(limiter.tryConsume(tenant, "LAUNCH", capacity)).isFalse();
    } finally {
      secondClient.shutdown();
    }
  }

  @Test
  void greedyRefillReplenishesTokensOverTime() throws InterruptedException {
    String tenant = uniqueTenant();
    // capacity=60/min → greedy 补充 1 token/s。先把桶抽干(容忍抽取期间的少量 greedy 回填),
    // 再等待 ~1.5s 让 refill 至少补回 1 个令牌。
    long capacity = 60;
    boolean rejectedWhenDrained = false;
    for (int i = 0; i < capacity + 5; i++) {
      if (!limiter.tryConsume(tenant, "TASK_REPORT", capacity)) {
        rejectedWhenDrained = true;
        break;
      }
    }
    assertThat(rejectedWhenDrained).as("bucket should reject once drained").isTrue();

    Thread.sleep(1_500L);

    assertThat(limiter.tryConsume(tenant, "TASK_REPORT", capacity))
        .as("greedy refill should replenish at least one token after ~1.5s")
        .isTrue();
  }

  @Test
  void nonPositiveMaxAlwaysAllows() {
    String tenant = uniqueTenant();
    assertThat(limiter.tryConsume(tenant, "LAUNCH", 0)).isTrue();
    assertThat(limiter.tryConsume(tenant, "LAUNCH", -1)).isTrue();
  }
}
