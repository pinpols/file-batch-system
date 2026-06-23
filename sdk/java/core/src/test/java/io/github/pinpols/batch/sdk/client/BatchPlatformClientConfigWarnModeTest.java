package io.github.pinpols.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R3-4(Round-2 P0 #4):时序校验严格度降级开关测试。
 *
 * <ul>
 *   <li>strict=true(默认)违反 → 抛 {@link IllegalStateException}(行为不变)
 *   <li>strict=false 违反 → WARN 不抛,client 仍可 build,避免 K8s 重启循环
 *   <li>{@code BATCH_SDK_STRICT_TIMING} env 覆盖按预期解析
 * </ul>
 */
class BatchPlatformClientConfigWarnModeTest {

  private static BatchPlatformClientConfig.BatchPlatformClientConfigBuilder valid() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("https://batch.example.com")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.tx.*")
        .kafkaGroupId("tx-workers");
  }

  // ─── strict=true(默认)行为不变 ───────────────────────────────────────────
  @Test
  @DisplayName("strict=true(默认)违反时序 → 仍 fail-fast IllegalStateException")
  void shouldThrowWhenStrictTrueAndViolated() {
    BatchPlatformClientConfig c =
        valid().heartbeatInterval(Duration.ofMillis(500)).build(); // hb < 1s
    assertThat(c.isStrictTimingValidation()).isTrue();
    assertThatThrownBy(c::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("heartbeatInterval");
  }

  // ─── strict=false 降级 WARN ───────────────────────────────────────────────
  @Test
  @DisplayName("strict=false 违反 hb<1s → 不抛,validate 通过")
  void shouldWarnInsteadOfThrowWhenStrictFalse_heartbeat() {
    BatchPlatformClientConfig c =
        valid().strictTimingValidation(false).heartbeatInterval(Duration.ofMillis(500)).build();
    assertThat(c.isStrictTimingValidation()).isFalse();
    assertThatCode(c::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("strict=false 违反 lease > hb×3 → 不抛")
  void shouldWarnWhenStrictFalse_leaseUpperBound() {
    BatchPlatformClientConfig c =
        valid()
            .strictTimingValidation(false)
            .heartbeatInterval(Duration.ofSeconds(10))
            .leaseRenewInterval(Duration.ofSeconds(40))
            .httpTimeout(Duration.ofSeconds(5))
            .build();
    assertThatCode(c::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("strict=false 违反 httpTimeout > hb/2 → 不抛")
  void shouldWarnWhenStrictFalse_httpTimeout() {
    BatchPlatformClientConfig c =
        valid()
            .strictTimingValidation(false)
            .heartbeatInterval(Duration.ofSeconds(10))
            .leaseRenewInterval(Duration.ofSeconds(20))
            .httpTimeout(Duration.ofSeconds(8))
            .build();
    assertThatCode(c::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("strict=false + 配置稍偏 → BatchPlatformClient.builder 仍可 build(避免 K8s 重启循环)")
  void shouldAllowClientBuildWhenStrictFalse() {
    BatchPlatformClientConfig bad =
        valid().strictTimingValidation(false).heartbeatInterval(Duration.ofMillis(500)).build();
    assertThatCode(
            () ->
                BatchPlatformClient.builder(bad)
                    .register(
                        new SdkTaskHandler() {
                          @Override
                          public String taskType() {
                            return "t";
                          }

                          @Override
                          public SdkTaskResult execute(SdkTaskContext ctx) {
                            return SdkTaskResult.ok();
                          }
                        })
                    .build())
        .doesNotThrowAnyException();
  }

  // ─── 默认值 + strict=false 也 OK(确保不引入新断言) ────────────────────────
  @Test
  @DisplayName("strict=false 但默认值合法 → 不抛,无 WARN 也合理")
  void shouldStayCleanWhenStrictFalseAndDefaultsValid() {
    BatchPlatformClientConfig c = valid().strictTimingValidation(false).build();
    assertThatCode(c::validate).doesNotThrowAnyException();
  }

  // ─── fromEnv: BATCH_SDK_STRICT_TIMING ─────────────────────────────────────
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
  @DisplayName("env 未设 STRICT_TIMING → 默认 strictTimingValidation=true")
  void shouldDefaultStrictTrueWhenEnvAbsent() {
    BatchPlatformClientConfig c =
        BatchPlatformClientConfig.fromEnv("BATCH_SDK_", minimalEnv()::get);
    assertThat(c.isStrictTimingValidation()).isTrue();
  }

  @Test
  @DisplayName("env BATCH_SDK_STRICT_TIMING=false → strictTimingValidation=false 且违反时序 fromEnv 不抛")
  void shouldDowngradeWhenEnvFalse() {
    Map<String, String> env = minimalEnv();
    env.put("BATCH_SDK_STRICT_TIMING", "false");
    // 配置 hb=500ms 违反规则 → strict=true 时 fromEnv 会抛;strict=false 应当只 WARN
    env.put("BATCH_SDK_HEARTBEAT_INTERVAL_SECONDS", "0"); // 0s 也违反 hb >= 1s
    // 注:HEARTBEAT_INTERVAL_SECONDS 只接受秒级 long,这里用 0s 模拟违反
    BatchPlatformClientConfig c = BatchPlatformClientConfig.fromEnv("BATCH_SDK_", env::get);
    assertThat(c.isStrictTimingValidation()).isFalse();
    assertThat(c.getHeartbeatInterval()).isEqualTo(Duration.ZERO);
  }

  @Test
  @DisplayName("env BATCH_SDK_STRICT_TIMING=true → 保持 strict,违反时序 fromEnv 抛 IllegalStateException")
  void shouldStayStrictWhenEnvTrue() {
    Map<String, String> env = minimalEnv();
    env.put("BATCH_SDK_STRICT_TIMING", "true");
    env.put("BATCH_SDK_HEARTBEAT_INTERVAL_SECONDS", "0");
    assertThatThrownBy(() -> BatchPlatformClientConfig.fromEnv("BATCH_SDK_", env::get))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("heartbeatInterval");
  }

  @Test
  @DisplayName("parseBoolean: 各种取值")
  void shouldParseBooleanForms() {
    // 显式 false 系列 → false
    assertThat(BatchPlatformClientConfig.parseBoolean("false")).isFalse();
    assertThat(BatchPlatformClientConfig.parseBoolean("FALSE")).isFalse();
    assertThat(BatchPlatformClientConfig.parseBoolean("0")).isFalse();
    assertThat(BatchPlatformClientConfig.parseBoolean("no")).isFalse();
    assertThat(BatchPlatformClientConfig.parseBoolean("off")).isFalse();
    // 其余(含非法值)→ true(默认 strict 安全偏好)
    assertThat(BatchPlatformClientConfig.parseBoolean("true")).isTrue();
    assertThat(BatchPlatformClientConfig.parseBoolean("1")).isTrue();
    assertThat(BatchPlatformClientConfig.parseBoolean("yes")).isTrue();
    assertThat(BatchPlatformClientConfig.parseBoolean("garbage")).isTrue();
  }
}
