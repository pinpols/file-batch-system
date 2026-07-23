package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class BusinessDataSourceBuilderTest {

  @Test
  void enabledRoutingWithoutShards_shouldFailBeforeCreatingDataSource() {
    BusinessRoutingProperties routing = new BusinessRoutingProperties();
    routing.setEnabled(true);
    routing.setShards(Collections.emptyList());

    assertThatThrownBy(
            () ->
                BusinessDataSourceBuilder.build(
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
}
