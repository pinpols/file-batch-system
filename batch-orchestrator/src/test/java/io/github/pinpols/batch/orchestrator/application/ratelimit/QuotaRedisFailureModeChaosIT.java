package io.github.pinpols.batch.orchestrator.application.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService.QuotaReservationOwner;
import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService.QuotaReservationPolicy;
import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService.QuotaReservationReason;
import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService.QuotaReservationRequest;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.infrastructure.quota.RedisQuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import io.github.pinpols.batch.testing.chaos.AbstractChaosIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** {@code batch.quota.redis.failure-mode} 开关集成测试：Redis 全断（toxiproxy）时 FAIL_CLOSED 拒单 / FAIL_OPEN 放行。 */
@DisplayName("Redis 故障 → quota failure-mode 语义")
class QuotaRedisFailureModeChaosIT extends AbstractChaosIntegrationTest {

  private static final QuotaReservationRequest REQUEST = new QuotaReservationRequest(
      new QuotaReservationOwner("t1", "TENANT", "chaos-owner"),
      // SLIDING_WINDOW（runtime-managed）+ burst>0 → 强制走 Redis Lua 运行时判定，而非静态 allow
      new QuotaReservationPolicy("SLIDING_WINDOW", 5, 2, 1),
      0,
      1,
      new QuotaReservationReason("TEST", "chaos-it"));

  @Test
  @DisplayName("Redis 断 → FAIL_CLOSED 返回 QUOTA_BACKEND_UNAVAILABLE；FAIL_OPEN 放行")
  void redisDownRespectsFailureMode() throws Exception {
    LettuceConnectionFactory factory = newLettuceFactory();
    try {
      OrchestratorRedisSupport redis =
          new OrchestratorRedisSupport(new StringRedisTemplate(factory), new ObjectMapper());
      BatchTimezoneProvider timezoneProvider =
          new BatchTimezoneProvider(new BatchTimezoneProperties());
      RedisQuotaRuntimeStateService closed =
          new RedisQuotaRuntimeStateService(redis, timezoneProvider, "FAIL_CLOSED");
      RedisQuotaRuntimeStateService open =
          new RedisQuotaRuntimeStateService(redis, timezoneProvider, "FAIL_OPEN");

      // 健康路径：Redis 可用时两种模式都正常判定额度
      assertThat(closed.evaluateAndReserve(REQUEST).allowed()).isTrue();
      assertThat(open.evaluateAndReserve(REQUEST).allowed()).isTrue();

      withDown(ProxyTarget.REDIS, () -> {
        ResourceCheck closedCheck = closed.evaluateAndReserve(REQUEST);
        assertThat(closedCheck.allowed()).as("FAIL_CLOSED: Redis 故障时不能绕过租户配额").isFalse();
        assertThat(closedCheck.reasonCode()).isEqualTo("QUOTA_BACKEND_UNAVAILABLE");

        ResourceCheck openCheck = open.evaluateAndReserve(REQUEST);
        assertThat(openCheck.allowed()).as("FAIL_OPEN: Redis 故障时放行（仅限本地兼容场景）").isTrue();
      });
    } finally {
      factory.destroy();
    }
  }

  private LettuceConnectionFactory newLettuceFactory() {
    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(redisProxiedHost(), redisProxiedPort());
    LettuceClientConfiguration client = LettuceClientConfiguration.builder()
        .commandTimeout(Duration.ofSeconds(3))
        .build();
    LettuceConnectionFactory factory = new LettuceConnectionFactory(config, client);
    factory.afterPropertiesSet();
    return factory;
  }
}
