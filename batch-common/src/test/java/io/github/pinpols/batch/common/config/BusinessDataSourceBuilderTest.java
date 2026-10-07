package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("业务数据源构建:分片路由开启时的分片列表、默认分片与分片键唯一性前置校验")
class BusinessDataSourceBuilderTest {

  @Test
  @DisplayName("开启分片路由却未配置任何分片时,在创建连接池之前即失败并提示分片配置缺失")
  void enabledRoutingWithoutShards_shouldFailBeforeCreatingDataSource() {
    BusinessRoutingProperties routing = new BusinessRoutingProperties();
    routing.setEnabled(true);
    routing.setShards(Collections.emptyList());

    assertThatThrownBy(() -> BusinessDataSourceBuilder.build(
            new HikariConfig(),
            new BusinessDataSourceProperties(),
            new BatchPgSessionProperties(),
            routing,
            null,
            "test-worker"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("routing.enabled=true")
        .hasMessageContaining("shard");
  }

  @Test
  @DisplayName("开启分片路由但缺少默认分片时,在创建连接池之前提示默认分片不存在")
  void enabledRoutingWithoutDefaultShard_shouldFailBeforeCreatingDataSource() {
    BusinessRoutingProperties routing = routingWithShards(shard("shard-1"));

    assertThatThrownBy(() -> build(routing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("default key shard-0");
  }

  @Test
  @DisplayName("两个分片使用相同分片键时,在创建连接池之前提示键重复并指明具体分片键")
  void enabledRoutingWithDuplicateShardKey_shouldFailBeforeCreatingDataSource() {
    BusinessRoutingProperties routing = routingWithShards(shard("shard-0"), shard("shard-0"));

    assertThatThrownBy(() -> build(routing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate")
        .hasMessageContaining("shard-0");
  }

  private static DataSource build(BusinessRoutingProperties routing) {
    return BusinessDataSourceBuilder.build(
        new HikariConfig(),
        new BusinessDataSourceProperties(),
        new BatchPgSessionProperties(),
        routing,
        null,
        "test-worker");
  }

  private static BusinessRoutingProperties routingWithShards(
      BusinessRoutingProperties.Shard... shards) {
    BusinessRoutingProperties routing = new BusinessRoutingProperties();
    routing.setEnabled(true);
    routing.setShards(List.of(shards));
    return routing;
  }

  private static BusinessRoutingProperties.Shard shard(String key) {
    BusinessRoutingProperties.Shard shard = new BusinessRoutingProperties.Shard();
    shard.setKey(key);
    shard.setUrl("jdbc:postgresql://127.0.0.1:5432/batch_business");
    shard.setUsername("batch");
    shard.setPassword("test-only");
    return shard;
  }
}
