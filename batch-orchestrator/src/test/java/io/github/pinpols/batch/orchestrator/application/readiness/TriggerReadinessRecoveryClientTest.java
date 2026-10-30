package io.github.pinpols.batch.orchestrator.application.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.ReadinessWaitEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.http.OkHttpOrchestratorExternalHttpTransport;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("readiness 恢复 HTTP: 响应挂起超时后继续下一次调用")
class TriggerReadinessRecoveryClientTest {
  @Test
  @DisplayName("已连接但延迟响应时有界失败, 下一次正常恢复仍成功")
  void shouldTimeoutAndRecover_whenResponseStalls() throws Exception {
    MockWebServer server = new MockWebServer();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      server.start();
      server.enqueue(
          new MockResponse.Builder().code(200).headersDelay(3, TimeUnit.SECONDS).build());
      server.enqueue(new MockResponse.Builder().code(200).build());
      TriggerInternalProperties properties = new TriggerInternalProperties();
      properties.setBaseUrl(server.url("/").toString());
      properties.setConnectTimeout(Duration.ofMillis(200));
      properties.setRequestTimeout(Duration.ofMillis(200));
      BatchSecurityProperties security = new BatchSecurityProperties();
      security.setInternalSecret("test-readiness-secret");
      TriggerReadinessRecoveryClient client = new TriggerReadinessRecoveryClient(
          properties, security, new OkHttpOrchestratorExternalHttpTransport());
      ReadinessWaitEntity wait = new ReadinessWaitEntity();
      wait.setTenantId("tenant-recovery");
      wait.setWorkflowDefinitionId(7L);
      wait.setScheduledFireTime(Instant.parse("2026-10-26T00:00:00Z"));

      Future<TriggerReadinessRecoveryClient.RecoveryOutcome> pending =
          executor.submit(() -> client.recover(wait));
      TriggerReadinessRecoveryClient.RecoveryOutcome failed = pending.get(2, TimeUnit.SECONDS);
      TriggerReadinessRecoveryClient.RecoveryOutcome recovered = client.recover(wait);

      assertThat(failed).isEqualTo(TriggerReadinessRecoveryClient.RecoveryOutcome.FAILED);
      assertThat(recovered).isEqualTo(TriggerReadinessRecoveryClient.RecoveryOutcome.LAUNCHED);
    } finally {
      server.close();
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "200,,LAUNCHED",
    "409,ALREADY_TERMINATED,ALREADY_TERMINATED",
    "409,NOT_READY,NOT_READY",
    "503,,FAILED",
    "401,,FAILED"
  })
  @DisplayName("受控传输保留成功、未就绪、已终结和失败的恢复处置")
  void shouldPreserveRecoveryOutcomes_whenStatusReturned(
      int status, String returnedOutcome, TriggerReadinessRecoveryClient.RecoveryOutcome expected) {
    TriggerInternalProperties properties = new TriggerInternalProperties();
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setInternalSecret("test-readiness-secret");
    String body =
        returnedOutcome == null ? "" : JsonUtils.toJson(Map.of("outcome", returnedOutcome));
    TriggerReadinessRecoveryClient client = new TriggerReadinessRecoveryClient(
        properties, security, request -> new OutboundHttpResponse(status, body));
    ReadinessWaitEntity wait = new ReadinessWaitEntity();
    wait.setTenantId("tenant-recovery");
    wait.setWorkflowDefinitionId(7L);
    wait.setScheduledFireTime(Instant.parse("2026-10-26T00:00:00Z"));

    TriggerReadinessRecoveryClient.RecoveryOutcome actual = client.recover(wait);

    assertThat(actual).isEqualTo(expected);
  }
}
