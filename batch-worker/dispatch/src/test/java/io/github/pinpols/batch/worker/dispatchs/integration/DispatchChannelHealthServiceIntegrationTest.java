package io.github.pinpols.batch.worker.dispatchs.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelHealthRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelHealthService;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelHealthSnapshot;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration test: DispatchChannelHealthService records outcomes to the real business DB and
 * retrieves consistent health snapshots.
 */
@SpringBootTest(
    classes = BatchWorkerDispatchApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("渠道健康服务集成:真实业务库上的健康快照落库、失败退避累积与放行判定")
class DispatchChannelHealthServiceIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  private DispatchChannelHealthService healthService;

  @Autowired
  private DispatchChannelHealthRepository healthRepository;

  @Test
  @DisplayName("投递成功落库健康快照:状态健康、连续失败数为零,并记录最后成功时间与探测信息")
  void shouldPersistHealthySnapshotOnSuccessfulDispatch() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-001", "API");

    healthService.recordDispatchOutcome(channelConfig, true, "ok", null);

    DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-001");
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.tenantId()).isEqualTo("t1");
    assertThat(snapshot.channelCode()).isEqualTo("ch-001");
    assertThat(snapshot.channelType()).isEqualTo("API");
    assertThat(snapshot.healthStatus()).isEqualTo("HEALTHY");
    assertThat(snapshot.consecutiveFailures()).isZero();
    assertThat(snapshot.lastSuccessAt()).isNotNull();
    assertThat(snapshot.probeMessage()).isEqualTo("ok");
  }

  @Test
  @DisplayName("连续两次投递失败后,连续失败数累加到二且健康状态降级")
  void shouldIncrementConsecutiveFailuresOnFailure() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-002", "SFTP");

    healthService.recordDispatchOutcome(channelConfig, false, "timeout", null);
    healthService.recordDispatchOutcome(channelConfig, false, "timeout", null);

    DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-002");
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.consecutiveFailures()).isEqualTo(2);
    assertThat(snapshot.lastFailureAt()).isNotNull();
    assertThat(snapshot.probeMessage()).isEqualTo("timeout");
    assertThat(snapshot.healthStatus()).isEqualTo("DEGRADED");
  }

  @Test
  @DisplayName("八路并发记录失败时失败次数不丢,退避窗口按最新一次失败推算且不少于十四分钟")
  void shouldKeepLatestExponentialBackoff_whenFailuresAreConcurrent() throws Exception {
    int concurrency = 8;
    Map<String, Object> channelConfig = channelConfig("t1", "ch-concurrent", "API");
    CountDownLatch ready = new CountDownLatch(concurrency);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(concurrency)) {
      for (int i = 0; i < concurrency; i++) {
        executor.submit(() -> {
          ready.countDown();
          start.await();
          healthService.recordDispatchOutcome(channelConfig, false, "timeout", null);
          return null;
        });
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-concurrent");
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.consecutiveFailures()).isEqualTo(concurrency);
    assertThat(Duration.between(snapshot.lastFailureAt(), snapshot.nextProbeAt()))
        .isGreaterThanOrEqualTo(Duration.ofMinutes(14));
  }

  @Test
  @DisplayName("失败之后投递成功时,状态恢复健康且连续失败数清零")
  void shouldResetConsecutiveFailuresAfterSuccess() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-003", "NAS");

    healthService.recordDispatchOutcome(channelConfig, false, "error", null);
    healthService.recordDispatchOutcome(channelConfig, false, "error", null);
    healthService.recordDispatchOutcome(channelConfig, true, "ok", null);

    DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-003");
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.healthStatus()).isEqualTo("HEALTHY");
    assertThat(snapshot.consecutiveFailures()).isZero();
    assertThat(snapshot.probeMessage()).isEqualTo("ok");
  }

  @Test
  @DisplayName("渠道尚无健康快照时默认放行投递")
  void shouldAllowDispatchWhenNoHealthSnapshotExists() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-new-999", "API");
    // no prior health record — should default to allow
    assertThat(healthService.allowDispatch(channelConfig)).isTrue();
  }

  @Test
  @DisplayName("渠道健康快照为健康状态时放行投递")
  void shouldAllowDispatchForHealthyChannel() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-004", "API");
    healthService.recordDispatchOutcome(channelConfig, true, "ok", null);

    assertThat(healthService.allowDispatch(channelConfig)).isTrue();
  }

  @Test
  @DisplayName("渠道连续失败进入不健康且退避尚未到期时,投递被拒绝放行")
  void shouldBlockDispatchForUnhealthyChannelBeforeBackoffExpires() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-005", "API");
    // trigger enough failures to set UNHEALTHY (default threshold is 5)
    for (int i = 0; i < 5; i++) {
      healthService.recordDispatchOutcome(channelConfig, false, "error", null);
    }

    DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-005");
    assertThat(snapshot.healthStatus()).isIn("UNHEALTHY", "DEGRADED");
    // backoff has not expired, so dispatch should be blocked
    assertThat(healthService.allowDispatch(channelConfig)).isFalse();
  }

  // --- helpers ---

  private static Map<String, Object> channelConfig(
      String tenantId, String channelCode, String channelType) {
    return Map.of(
        "tenant_id", tenantId,
        "channel_code", channelCode,
        "channel_type", channelType);
  }
}
