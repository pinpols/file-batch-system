package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.orchestrator.infrastructure.sharding.RedisShardAssignmentProvider;
import io.github.pinpols.batch.orchestrator.infrastructure.sharding.ShardAssignmentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class ShardingConfigurationTest {

  private final ShardingConfiguration configuration = new ShardingConfiguration();
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @Test
  void dynamicModeBuildsRedisProviderForValidLeaseWindow() {
    OutboxProperties properties = dynamicProperties(5_000, 30_000);
    properties.getSharding().setMemberId("orchestrator-0");

    ShardAssignmentProvider provider = configuration.shardAssignmentProvider(properties, redis);

    assertThat(provider).isInstanceOf(RedisShardAssignmentProvider.class);
  }

  @Test
  void dynamicModeRejectsNonPositiveHeartbeat() {
    OutboxProperties properties = dynamicProperties(0, 30_000);

    assertThatIllegalStateException()
        .isThrownBy(() -> configuration.shardAssignmentProvider(properties, redis))
        .withMessageContaining("heartbeat-interval-ms");
  }

  @Test
  void dynamicModeRejectsTtlShorterThanThreeHeartbeats() {
    OutboxProperties properties = dynamicProperties(5_000, 14_999);

    assertThatIllegalStateException()
        .isThrownBy(() -> configuration.shardAssignmentProvider(properties, redis))
        .withMessageContaining("member-ttl-ms");
  }

  @Test
  void dynamicModeRejectsBlankMembersKey() {
    OutboxProperties properties = dynamicProperties(5_000, 30_000);
    properties.getSharding().setMembersKey(" ");

    assertThatIllegalStateException()
        .isThrownBy(() -> configuration.shardAssignmentProvider(properties, redis))
        .withMessageContaining("members-key");
  }

  private OutboxProperties dynamicProperties(long heartbeatMs, long ttlMs) {
    OutboxProperties properties = new OutboxProperties();
    properties.setShardingMode(OutboxProperties.ShardingMode.DYNAMIC);
    properties.getSharding().setHeartbeatIntervalMs(heartbeatMs);
    properties.getSharding().setMemberTtlMs(ttlMs);
    return properties;
  }
}
