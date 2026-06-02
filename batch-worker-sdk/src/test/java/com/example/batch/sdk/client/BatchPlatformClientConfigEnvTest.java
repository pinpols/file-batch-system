package com.example.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BatchPlatformClientConfigEnvTest {

  private Map<String, String> minimalEnv() {
    Map<String, String> env = new HashMap<>();
    env.put("BATCH_SDK_BASE_URL", "http://platform:8080");
    env.put("BATCH_SDK_TENANT_ID", "t1");
    env.put("BATCH_SDK_WORKER_CODE", "w1");
    env.put("BATCH_SDK_KAFKA_BOOTSTRAP", "kafka:9092");
    env.put("BATCH_SDK_KAFKA_TOPIC_PATTERN", "batch.task.dispatch.t1.*");
    env.put("BATCH_SDK_KAFKA_GROUP_ID", "g1");
    return env;
  }

  @Test
  void shouldBuildFromRequiredEnvWithDefaults() {
    BatchPlatformClientConfig config =
        BatchPlatformClientConfig.fromEnv("BATCH_SDK_", minimalEnv()::get);

    assertThat(config.getBaseUrl()).isEqualTo("http://platform:8080");
    assertThat(config.getTenantId()).isEqualTo("t1");
    assertThat(config.getKafkaGroupId()).isEqualTo("g1");
    assertThat(config.getMaxConcurrentTasks()).isEqualTo(4);
    assertThat(config.getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void shouldApplyOptionalOverrides() {
    Map<String, String> env = minimalEnv();
    env.put("BATCH_SDK_MAX_CONCURRENT_TASKS", "8");
    env.put("BATCH_SDK_HEARTBEAT_INTERVAL_SECONDS", "15");
    // Lane I:覆盖默认 60s 让 lease <= heartbeat × 3(45s) 通过校验
    env.put("BATCH_SDK_LEASE_RENEW_INTERVAL_SECONDS", "30");
    env.put("BATCH_SDK_HTTP_TIMEOUT_SECONDS", "5");
    env.put("BATCH_SDK_API_KEY", "secret");
    env.put("BATCH_SDK_BUILD_ID", "abc123");

    BatchPlatformClientConfig config = BatchPlatformClientConfig.fromEnv("BATCH_SDK_", env::get);

    assertThat(config.getMaxConcurrentTasks()).isEqualTo(8);
    assertThat(config.getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
    assertThat(config.getLeaseRenewInterval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(config.getApiKey()).isEqualTo("secret");
    assertThat(config.getBuildId()).isEqualTo("abc123");
  }

  @Test
  void shouldReportAllMissingRequiredKeysAtOnce() {
    Map<String, String> env = new HashMap<>();
    env.put("BATCH_SDK_BASE_URL", "http://platform:8080");

    assertThatThrownBy(() -> BatchPlatformClientConfig.fromEnv("BATCH_SDK_", env::get))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BATCH_SDK_TENANT_ID")
        .hasMessageContaining("BATCH_SDK_KAFKA_GROUP_ID");
  }

  @Test
  void shouldRejectBaseUrlWithTrailingSlash() {
    Map<String, String> env = minimalEnv();
    env.put("BATCH_SDK_BASE_URL", "http://platform:8080/");

    assertThatThrownBy(() -> BatchPlatformClientConfig.fromEnv("BATCH_SDK_", env::get))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUrl");
  }
}
