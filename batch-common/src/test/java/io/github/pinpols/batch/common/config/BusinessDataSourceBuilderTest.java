package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class BusinessDataSourceBuilderTest {

  @Test
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
  void enabledRoutingWithoutDefaultShard_shouldFailBeforeCreatingDataSource() {
    BusinessRoutingProperties routing = routingWithShards(shard("shard-1"));

    assertThatThrownBy(() -> build(routing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("default key shard-0");
  }

  @Test
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
