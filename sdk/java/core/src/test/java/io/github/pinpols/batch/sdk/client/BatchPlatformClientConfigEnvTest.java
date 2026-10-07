package io.github.pinpols.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BatchPlatformClientConfig 环境变量装载 — 必填项汇总与可选覆盖项解析")
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
  @DisplayName("仅提供必填环境变量时套用并发数与心跳间隔等默认值")
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
  @DisplayName("可选环境变量覆盖并发、心跳、租约与重试相关默认值")
  void shouldApplyOptionalOverrides() {
    Map<String, String> env = minimalEnv();
    env.put("BATCH_SDK_MAX_CONCURRENT_TASKS", "8");
    env.put("BATCH_SDK_HEARTBEAT_INTERVAL_SECONDS", "15");
    // Lane I:覆盖默认 60s 让 lease <= heartbeat × 3(45s) 通过校验
    env.put("BATCH_SDK_LEASE_RENEW_INTERVAL_SECONDS", "30");
    env.put("BATCH_SDK_HTTP_TIMEOUT_SECONDS", "5");
    env.put("BATCH_SDK_API_KEY", "secret");
    env.put("BATCH_SDK_BUILD_ID", "abc123");
    env.put("BATCH_SDK_RETRY_MAX_ATTEMPTS", "4");
    env.put("BATCH_SDK_RETRY_BASE_DELAY_MS", "350");
    env.put("BATCH_SDK_CLIENT_ERROR_FAIL_FAST_THRESHOLD", "7");

    BatchPlatformClientConfig config = BatchPlatformClientConfig.fromEnv("BATCH_SDK_", env::get);

    assertThat(config.getMaxConcurrentTasks()).isEqualTo(8);
    assertThat(config.getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
    assertThat(config.getLeaseRenewInterval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(config.getApiKey()).isEqualTo("secret");
    assertThat(config.getBuildId()).isEqualTo("abc123");
    assertThat(config.getClaimMax5xxRetries()).isEqualTo(3);
    assertThat(config.getClaimRetryBaseDelay()).isEqualTo(Duration.ofMillis(350));
    assertThat(config.getClientErrorFailFastThreshold()).isEqualTo(7);
  }

  @Test
  @DisplayName("缺失多个必填项时一次性汇总报出全部键名")
  void shouldReportAllMissingRequiredKeysAtOnce() {
    Map<String, String> env = new HashMap<>();
    env.put("BATCH_SDK_BASE_URL", "http://platform:8080");

    assertThatThrownBy(() -> BatchPlatformClientConfig.fromEnv("BATCH_SDK_", env::get))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BATCH_SDK_TENANT_ID")
        .hasMessageContaining("BATCH_SDK_KAFKA_GROUP_ID");
  }

  @Test
  @DisplayName("基址以斜杠结尾时拒绝装载配置")
  void shouldRejectBaseUrlWithTrailingSlash() {
    Map<String, String> env = minimalEnv();
    env.put("BATCH_SDK_BASE_URL", "http://platform:8080/");

    assertThatThrownBy(() -> BatchPlatformClientConfig.fromEnv("BATCH_SDK_", env::get))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUrl");
  }
}
