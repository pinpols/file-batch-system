package com.example.batch.orchestrator.infrastructure.sharding;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.config.OutboxProperties;
import org.junit.jupiter.api.Test;

class StaticShardAssignmentProviderTest {

  @Test
  void defaultSingleModeReturnsSingle() {
    OutboxProperties props = new OutboxProperties();
    // 默认 shardTotal=1, shardIndex=0
    StaticShardAssignmentProvider provider = new StaticShardAssignmentProvider(props);

    ShardAssignment a = provider.current();
    assertThat(a.shardTotal()).isEqualTo(1);
    assertThat(a.shardIndex()).isEqualTo(0);
  }

  @Test
  void multiShardReturnsConfiguredValues() {
    OutboxProperties props = new OutboxProperties();
    props.setShardTotal(4);
    props.setShardIndex(2);
    StaticShardAssignmentProvider provider = new StaticShardAssignmentProvider(props);

    ShardAssignment a = provider.current();
    assertThat(a.shardTotal()).isEqualTo(4);
    assertThat(a.shardIndex()).isEqualTo(2);
  }

  @Test
  void singleTotalWithNonZeroIndexFallsBackToSingle() {
    // 回退：shardTotal=1 无论 shardIndex 写成啥（配置错）都视为 single
    OutboxProperties props = new OutboxProperties();
    props.setShardTotal(1);
    props.setShardIndex(5); // 无效，但 provider 直接返回 single 不抛异常
    StaticShardAssignmentProvider provider = new StaticShardAssignmentProvider(props);

    ShardAssignment a = provider.current();
    assertThat(a.shardTotal()).isEqualTo(1);
    assertThat(a.shardIndex()).isEqualTo(0);
  }

  @Test
  void zeroOrNegativeTotalCoercesTo1() {
    // shardTotal<=0 的非法配置，静态 provider 容错为 1（避免启动期直接失败）
    OutboxProperties props = new OutboxProperties();
    props.setShardTotal(0);
    props.setShardIndex(0);
    StaticShardAssignmentProvider provider = new StaticShardAssignmentProvider(props);

    ShardAssignment a = provider.current();
    assertThat(a.shardTotal()).isEqualTo(1);
  }
}
