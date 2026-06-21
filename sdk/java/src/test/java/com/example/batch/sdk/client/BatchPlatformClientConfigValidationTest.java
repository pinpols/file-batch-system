package com.example.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Lane I:启动期 cross-field 时序校验测试 —— 覆盖 heartbeat / lease / httpTimeout 4 条规则正反 case。 */
class BatchPlatformClientConfigValidationTest {

  private static BatchPlatformClientConfig.BatchPlatformClientConfigBuilder valid() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("https://batch.example.com")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.tx.*")
        .kafkaGroupId("tx-workers");
  }

  // ─── heartbeatInterval >= 1s ───────────────────────────────────────────────
  @Test
  @DisplayName("heartbeatInterval < 1s → IllegalStateException(防刷爆 orch)")
  void shouldRejectHeartbeatTooSmall() {
    BatchPlatformClientConfig c = valid().heartbeatInterval(Duration.ofMillis(500)).build();
    assertThatThrownBy(c::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("heartbeatInterval")
        .hasMessageContaining(">= 1s");
  }

  @Test
  @DisplayName("heartbeatInterval = 2s + lease=5s + http=1s → 接受(各规则边界)")
  void shouldAcceptHeartbeatNearFloor() {
    // 注:hb=1s 时 lease 上限 = 3s 但 lease 下限 = 5s 自相冲突,故下限是 hb=2s 起
    BatchPlatformClientConfig ok =
        valid()
            .heartbeatInterval(Duration.ofSeconds(2))
            .leaseRenewInterval(Duration.ofSeconds(5))
            .httpTimeout(Duration.ofSeconds(1))
            .build();
    assertThatCode(ok::validate).doesNotThrowAnyException();
  }

  // ─── leaseRenewInterval >= 5s ──────────────────────────────────────────────
  @Test
  @DisplayName("leaseRenewInterval < 5s → IllegalStateException")
  void shouldRejectLeaseTooSmall() {
    BatchPlatformClientConfig c = valid().leaseRenewInterval(Duration.ofSeconds(3)).build();
    assertThatThrownBy(c::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("leaseRenewInterval")
        .hasMessageContaining(">= 5s");
  }

  // ─── leaseRenewInterval <= heartbeatInterval × 3 ───────────────────────────
  @Test
  @DisplayName("leaseRenewInterval > heartbeatInterval × 3 → IllegalStateException(防 task 被误回收)")
  void shouldRejectLeaseGreaterThanThreeHeartbeats() {
    // hb=10s × 3 = 30s,lease=40s 超出
    BatchPlatformClientConfig c =
        valid()
            .heartbeatInterval(Duration.ofSeconds(10))
            .leaseRenewInterval(Duration.ofSeconds(40))
            .httpTimeout(Duration.ofSeconds(5))
            .build();
    assertThatThrownBy(c::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("leaseRenewInterval")
        .hasMessageContaining("heartbeatInterval × 3");
  }

  @Test
  @DisplayName("leaseRenewInterval = heartbeatInterval × 3 → 接受(边界)")
  void shouldAcceptLeaseAtUpperBound() {
    BatchPlatformClientConfig c =
        valid()
            .heartbeatInterval(Duration.ofSeconds(10))
            .leaseRenewInterval(Duration.ofSeconds(30))
            .httpTimeout(Duration.ofSeconds(5))
            .build();
    assertThatCode(c::validate).doesNotThrowAnyException();
  }

  // ─── httpTimeout <= heartbeatInterval / 2 ──────────────────────────────────
  @Test
  @DisplayName("httpTimeout > heartbeatInterval / 2 → IllegalStateException(防超时堆积)")
  void shouldRejectHttpTimeoutTooLarge() {
    // hb=10s / 2 = 5s,httpTimeout=8s 超出
    BatchPlatformClientConfig c =
        valid()
            .heartbeatInterval(Duration.ofSeconds(10))
            .leaseRenewInterval(Duration.ofSeconds(20))
            .httpTimeout(Duration.ofSeconds(8))
            .build();
    assertThatThrownBy(c::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("httpTimeout")
        .hasMessageContaining("heartbeatInterval / 2");
  }

  @Test
  @DisplayName("httpTimeout = heartbeatInterval / 2 → 接受(边界)")
  void shouldAcceptHttpTimeoutAtUpperBound() {
    BatchPlatformClientConfig c =
        valid()
            .heartbeatInterval(Duration.ofSeconds(10))
            .leaseRenewInterval(Duration.ofSeconds(20))
            .httpTimeout(Duration.ofSeconds(5))
            .build();
    assertThatCode(c::validate).doesNotThrowAnyException();
  }

  // ─── 默认值组合 ────────────────────────────────────────────────────────────
  @Test
  @DisplayName("默认值(hb=30s, lease=60s, http=10s)符合所有规则")
  void shouldAcceptAllDefaults() {
    assertThatCode(valid().build()::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("BatchPlatformClient.builder 构造时验失败 → IllegalStateException 直接异常退出")
  void shouldFailFastInClientConstructor() {
    BatchPlatformClientConfig bad = valid().heartbeatInterval(Duration.ofMillis(100)).build();
    assertThatThrownBy(
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
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("BatchPlatformClient config invalid");
  }
}
