package io.github.pinpols.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BatchPlatformClientConfig — 默认值套用与基础字段校验")
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
  @DisplayName("字段齐备的合法配置校验通过且不抛异常")
  void shouldPassValidation_whenConfigComplete() {
    valid().build().validate(); // no throw
  }

  @Test
  @DisplayName("未显式覆盖时超时、心跳、并发与拉取间隔取默认值")
  void shouldApplyDefaultTimeouts_whenNotOverridden() {
    BatchPlatformClientConfig c = valid().build();
    assertThat(c.getHttpTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(c.getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(c.getMaxConcurrentTasks()).isEqualTo(4);
    assertThat(c.getKafkaPollInterval()).isEqualTo(Duration.ofMillis(200));
  }

  @Test
  @DisplayName("基址以斜杠结尾时校验失败并提示不得以斜杠结束")
  void shouldRejectBaseUrlWithTrailingSlash_whenValidated() {
    BatchPlatformClientConfig c = valid().baseUrl("https://batch.example.com/").build();
    assertThatThrownBy(c::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not end with '/'");
  }

  @Test
  @DisplayName("最大并发任务数为零或超过上限时校验失败")
  void shouldRejectMaxConcurrentTasks_whenOutOfRange() {
    BatchPlatformClientConfig zero = valid().maxConcurrentTasks(0).build();
    assertThatThrownBy(zero::validate).isInstanceOf(IllegalArgumentException.class);
    BatchPlatformClientConfig tooBig = valid().maxConcurrentTasks(65).build();
    assertThatThrownBy(tooBig::validate).isInstanceOf(IllegalArgumentException.class);
  }
}
