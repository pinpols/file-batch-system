package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.orchestrator.infrastructure.sharding.RedisShardAssignmentProvider;
import io.github.pinpols.batch.orchestrator.infrastructure.sharding.ShardAssignmentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

@DisplayName("动态分片模式下的分片提供者装配:心跳间隔,成员租约时长与成员键的合法性校验")
class ShardingConfigurationTest {

  private final ShardingConfiguration configuration = new ShardingConfiguration();
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @Test
  @DisplayName("动态分片模式且心跳与租约窗口合法时,装配基于 Redis 的分片提供者")
  void shouldBuildRedisShardProvider_whenHeartbeatAndLeaseValid() {
    OutboxProperties properties = dynamicProperties(5_000, 30_000);
    properties.getSharding().setMemberId("orchestrator-0");

    ShardAssignmentProvider provider = configuration.shardAssignmentProvider(properties, redis);

    assertThat(provider).isInstanceOf(RedisShardAssignmentProvider.class);
  }

  @Test
  @DisplayName("心跳间隔非正数时拒绝装配分片提供者,错误信息指向心跳间隔配置")
  void shouldRejectShardProvider_whenHeartbeatIntervalNotPositive() {
    OutboxProperties properties = dynamicProperties(0, 30_000);

    assertThatIllegalStateException()
        .isThrownBy(() -> configuration.shardAssignmentProvider(properties, redis))
        .withMessageContaining("heartbeat-interval-ms");
  }

  @Test
  @DisplayName("成员租约时长不足三个心跳周期时拒绝装配,错误信息指向成员租约配置")
  void shouldRejectShardProvider_whenMemberLeaseShorterThanThreeHeartbeats() {
    OutboxProperties properties = dynamicProperties(5_000, 14_999);

    assertThatIllegalStateException()
        .isThrownBy(() -> configuration.shardAssignmentProvider(properties, redis))
        .withMessageContaining("member-ttl-ms");
  }

  @Test
  @DisplayName("成员键为空白字符时拒绝装配,错误信息指向成员键配置")
  void shouldRejectShardProvider_whenMembersKeyBlank() {
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
