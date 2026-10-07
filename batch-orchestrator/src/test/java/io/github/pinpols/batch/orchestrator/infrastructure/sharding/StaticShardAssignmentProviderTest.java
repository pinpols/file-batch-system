package io.github.pinpols.batch.orchestrator.infrastructure.sharding;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.config.OutboxProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("静态分片分配提供者在默认与多分片配置下的取值与容错")
class StaticShardAssignmentProviderTest {

  @Test
  @DisplayName("默认配置下分片总数为 1 且分片序号为 0")
  void shouldReturnSingleShard_whenDefaultConfiguration() {
    OutboxProperties props = new OutboxProperties();
    // 默认 shardTotal=1, shardIndex=0
    StaticShardAssignmentProvider provider = new StaticShardAssignmentProvider(props);

    ShardAssignment a = provider.current();
    assertThat(a.shardTotal()).isEqualTo(1);
    assertThat(a.shardIndex()).isZero();
  }

  @Test
  @DisplayName("多分片配置下返回配置的分片总数与分片序号")
  void shouldReturnConfiguredShardValues_whenMultiShardEnabled() {
    OutboxProperties props = new OutboxProperties();
    props.setShardTotal(4);
    props.setShardIndex(2);
    StaticShardAssignmentProvider provider = new StaticShardAssignmentProvider(props);

    ShardAssignment a = provider.current();
    assertThat(a.shardTotal()).isEqualTo(4);
    assertThat(a.shardIndex()).isEqualTo(2);
  }

  @Test
  @DisplayName("分片总数为 1 但分片序号非 0 时按单分片处理且不抛异常")
  void shouldFallBackToSingleShard_whenTotalIsOneButIndexNonZero() {
    // 回退：shardTotal=1 无论 shardIndex 写成啥（配置错）都视为 single
    OutboxProperties props = new OutboxProperties();
    props.setShardTotal(1);
    props.setShardIndex(5); // 无效，但 provider 直接返回 single 不抛异常
    StaticShardAssignmentProvider provider = new StaticShardAssignmentProvider(props);

    ShardAssignment a = provider.current();
    assertThat(a.shardTotal()).isEqualTo(1);
    assertThat(a.shardIndex()).isZero();
  }

  @Test
  @DisplayName("分片总数非正数时容错为 1,避免启动阶段直接失败")
  void shouldCoerceToSingleShard_whenTotalNotPositive() {
    // shardTotal<=0 的非法配置，静态 provider 容错为 1（避免启动期直接失败）
    OutboxProperties props = new OutboxProperties();
    props.setShardTotal(0);
    props.setShardIndex(0);
    StaticShardAssignmentProvider provider = new StaticShardAssignmentProvider(props);

    ShardAssignment a = provider.current();
    assertThat(a.shardTotal()).isEqualTo(1);
  }
}
