package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.orchestrator.config.OutboxProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.mq.OutboxPublishCircuitBreaker;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import io.github.pinpols.batch.testing.chaos.AbstractChaosIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 故障注入意图:验证 Redis 全断(toxiproxy disable)下 outbox 熔断器 fail-open —— 这是 #762 修复的混沌级守护。
 *
 * <ul>
 *   <li>{@code allowNow()} 慢速路径查 Redis 抛异常时不外抛,fail-open 放行(outbox 事件已落 PG,投递不因 Redis 停摆);
 *   <li>{@code onAdvanceResult()} 写 Redis 抛异常时静默跳过,绝不把异常抛回 {@code OutboxPollScheduler}。
 * </ul>
 *
 * <p>单测(OutboxPublishCircuitBreakerTest)用 mock 抛 RedisConnectionFailureException 验语义;本 IT 用真实
 * Lettuce → Toxiproxy → Redis 链路验证 Redis 真断时抛出的实际异常类型也被 fail-open 兜住(防 catch 过窄漏掉
 * QueryTimeoutException 等 DataAccessException 子类)。
 */
@DisplayName("Redis 全断注入 — outbox 熔断器 allowNow/onAdvanceResult fail-open,不抛(投递不因 Redis 停摆)")
class OutboxCircuitBreakerRedisDownIntegrationTest extends AbstractChaosIntegrationTest {

  @Test
  @DisplayName("Redis 断 → allowNow 不抛且放行、onAdvanceResult 不抛(真实 Lettuce 异常类型也被兜住)")
  void outboxCircuitBreakerShouldFailOpenWhenRedisDown() throws Exception {
    LettuceConnectionFactory factory = newLettuceFactory();
    try {
      OutboxProperties props = new OutboxProperties();
      props.setCircuitBreakerEnabled(true);
      props.setCircuitBreakerFailureThresholdConsecutivePolls(3);
      props.setCircuitBreakerCooldownMillis(5000L);
      // pollIntervalMillis=0：关闭态本地缓存立即过期,强制每次 allowNow 都走慢速路径打 Redis,
      // 否则预热后的缓存会让 withDown 内首次 allowNow 命中快速路径、绕过 Redis-down 分支。
      props.setPollIntervalMillis(0L);
      BatchOrchestratorGovernanceProperties governance =
          mock(BatchOrchestratorGovernanceProperties.class);
      when(governance.outbox()).thenReturn(props);

      StringRedisTemplate template = new StringRedisTemplate(factory);
      OrchestratorRedisSupport redis = new OrchestratorRedisSupport(template, new ObjectMapper());
      OutboxPublishCircuitBreaker breaker =
          new OutboxPublishCircuitBreaker(
              governance, redis, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

      // 预热:Redis 正常时 allowNow 走通(闭合 → 放行)
      assertThat(breaker.allowNow()).isTrue();

      withDown(
          ProxyTarget.REDIS,
          () -> {
            // Redis 断:allowNow 不得抛,且 fail-open 放行(outbox 投递继续)
            assertThatCode(breaker::allowNow).doesNotThrowAnyException();
            assertThat(breaker.allowNow()).isTrue();
            // onAdvanceResult 不得把 Redis 异常抛回 OutboxPollScheduler
            assertThatCode(() -> breaker.onAdvanceResult(1)).doesNotThrowAnyException();
          });

      // 恢复后仍可正常查询(不残留坏状态)
      assertThat(breaker.allowNow()).isTrue();
    } finally {
      factory.destroy();
    }
  }

  private LettuceConnectionFactory newLettuceFactory() {
    RedisStandaloneConfiguration cfg =
        new RedisStandaloneConfiguration(redisProxiedHost(), redisProxiedPort());
    LettuceClientConfiguration clientCfg =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .shutdownTimeout(Duration.ZERO)
            .build();
    LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg, clientCfg);
    factory.afterPropertiesSet();
    return factory;
  }
}
