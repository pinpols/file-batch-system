package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.internal.PlatformHttpException;
import io.github.pinpols.batch.sdk.internal.ThrottledLogger;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TaskDispatcherRetryCoordinatorTest {

  private PlatformHttpClient httpClient;
  private TaskDispatcherRetryCoordinator coordinator;

  @BeforeEach
  void setUp() {
    httpClient = mock(PlatformHttpClient.class);
    coordinator = new TaskDispatcherRetryCoordinator(
        config(),
        httpClient,
        ThrottledLogger.create(
            LoggerFactory.getLogger(TaskDispatcherRetryCoordinatorTest.class),
            Duration.ofSeconds(1)));
  }

  @Test
  void marksFatalAndStopsClaimOnAuthenticationFailure() throws Exception {
    when(httpClient.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(401, "unauthorized"));

    TaskDispatcherRetryCoordinator.ClaimResult result =
        coordinator.claimWithRetry(message(), "claim-key", Map.of());

    assertThat(result.claimed()).isFalse();
    assertThat(coordinator.isFatal()).isTrue();
    verify(httpClient, times(1)).claim(anyLong(), anyString(), any());
  }

  @Test
  void treatsClaimConflictAsPeerOwnershipWithoutMarkingFatal() throws Exception {
    when(httpClient.claim(anyLong(), anyString(), any()))
        .thenThrow(new PlatformHttpException(409, "already claimed"));

    TaskDispatcherRetryCoordinator.ClaimResult result =
        coordinator.claimWithRetry(message(), "claim-key", Map.of());

    assertThat(result.claimed()).isFalse();
    assertThat(coordinator.isFatal()).isFalse();
    verify(httpClient, times(1)).claim(anyLong(), anyString(), any());
  }

  private static BatchPlatformClientConfig config() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("http://localhost:0")
        .tenantId("tenant-a")
        .workerCode("worker-a")
        .kafkaBootstrap("localhost:9092")
        .kafkaTopicPattern("batch.task.*")
        .kafkaGroupId("worker-a")
        .claimMax5xxRetries(0)
        .claimRetryBaseDelay(Duration.ZERO)
        .build();
  }

  private static TaskDispatchMessage message() {
    return new TaskDispatchMessage(
        7L, "tenant-a", "job-a", "IMPORT", "instance-a", Map.of(), Map.of());
  }
}
