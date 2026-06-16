package com.example.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BatchPlatformClientConfigTest {

  private static BatchPlatformClientConfig.BatchPlatformClientConfigBuilder valid() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("https://batch.example.com")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.tx.*")
        .kafkaGroupId("tx-workers");
  }

  @Test
  void validateOnGoodConfig() {
    valid().build().validate(); // no throw
  }

  @Test
  void defaultValuesApplied() {
    BatchPlatformClientConfig c = valid().build();
    assertThat(c.getHttpTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(c.getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(c.getMaxConcurrentTasks()).isEqualTo(4);
    assertThat(c.getKafkaPollInterval()).isEqualTo(Duration.ofMillis(200));
  }

  @Test
  void baseUrlWithTrailingSlashRejected() {
    BatchPlatformClientConfig c = valid().baseUrl("https://batch.example.com/").build();
    assertThatThrownBy(c::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not end with '/'");
  }

  @Test
  void maxConcurrentTasksRange() {
    BatchPlatformClientConfig zero = valid().maxConcurrentTasks(0).build();
    assertThatThrownBy(zero::validate).isInstanceOf(IllegalArgumentException.class);
    BatchPlatformClientConfig tooBig = valid().maxConcurrentTasks(65).build();
    assertThatThrownBy(tooBig::validate).isInstanceOf(IllegalArgumentException.class);
  }
}
