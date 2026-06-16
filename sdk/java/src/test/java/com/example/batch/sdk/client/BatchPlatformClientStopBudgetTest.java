package com.example.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.dispatcher.KafkaTaskConsumer;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.scheduler.HeartbeatScheduler;
import com.example.batch.sdk.scheduler.LeaseRenewalScheduler;
import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Lane E #4-Java + #5:{@link BatchPlatformClient#stop(Duration)} 预算重分配 + fatal-auth 跳 deactivate
 * 的端到端验证。Stop 顺序 / drain WARN 已在 {@link BatchPlatformClientStopTimeoutTest} 覆盖,这里只关心 Lane E 新增不变量。
 */
class BatchPlatformClientStopBudgetTest {

  private static BatchPlatformClientConfig cfg() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("https://batch.example.com")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.tx.*")
        .kafkaGroupId("g")
        .maxConcurrentTasks(2)
        .build();
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    f.set(target, value);
  }

  /** Lane E #4-Java:Kafka fatal auth 失败时 deactivate 跳过(凭据已坏,HTTP 也会 401)。 */
  @Test
  void stopSkipsDeactivateWhenKafkaAuthFatal() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg()).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    KafkaTaskConsumer kafka = mock(KafkaTaskConsumer.class);
    when(kafka.isFatalAuthFailure()).thenReturn(true);
    HeartbeatScheduler hb = mock(HeartbeatScheduler.class);
    LeaseRenewalScheduler lease = mock(LeaseRenewalScheduler.class);

    inject(client, "httpClient", http);
    inject(client, "dispatcher", dispatcher);
    inject(client, "kafkaConsumer", kafka);
    inject(client, "heartbeatScheduler", hb);
    inject(client, "leaseRenewalScheduler", lease);
    inject(client, "started", true);

    client.stop(Duration.ofMillis(500));

    verify(kafka).close(any(Duration.class));
    verify(dispatcher).stop(any(Duration.class));
    verify(hb).close();
    verify(lease).close();
    // 关键:fatal auth 时不调 deactivate
    verify(http, never()).deactivate(anyString(), any());
  }

  /** Lane E #4-Java:正常路径(无 fatal auth)仍会调 deactivate。 */
  @Test
  void stopCallsDeactivateWhenKafkaHealthy() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg()).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    KafkaTaskConsumer kafka = mock(KafkaTaskConsumer.class);
    when(kafka.isFatalAuthFailure()).thenReturn(false);
    HeartbeatScheduler hb = mock(HeartbeatScheduler.class);
    LeaseRenewalScheduler lease = mock(LeaseRenewalScheduler.class);

    inject(client, "httpClient", http);
    inject(client, "dispatcher", dispatcher);
    inject(client, "kafkaConsumer", kafka);
    inject(client, "heartbeatScheduler", hb);
    inject(client, "leaseRenewalScheduler", lease);
    inject(client, "started", true);

    client.stop(Duration.ofMillis(500));

    verify(http).deactivate(anyString(), any());
  }

  /** Lane E #5:Kafka close 收到的 Duration 应基于总预算的 ~15% 算出(±jitter)。 */
  @Test
  void stopAllocates15PercentOfBudgetToKafkaJoin() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg()).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    KafkaTaskConsumer kafka = mock(KafkaTaskConsumer.class);
    HeartbeatScheduler hb = mock(HeartbeatScheduler.class);
    LeaseRenewalScheduler lease = mock(LeaseRenewalScheduler.class);

    inject(client, "httpClient", http);
    inject(client, "dispatcher", dispatcher);
    inject(client, "kafkaConsumer", kafka);
    inject(client, "heartbeatScheduler", hb);
    inject(client, "leaseRenewalScheduler", lease);
    inject(client, "started", true);

    long totalMs = 10_000;
    client.stop(Duration.ofMillis(totalMs));

    // capture kafka close duration arg
    org.mockito.ArgumentCaptor<Duration> kafkaCap =
        org.mockito.ArgumentCaptor.forClass(Duration.class);
    verify(kafka).close(kafkaCap.capture());
    long kafkaMs = kafkaCap.getValue().toMillis();
    // 15% of 10s = 1500ms (允许 ±100ms jitter)
    assertThat(kafkaMs).isBetween(1_400L, 1_600L);

    // dispatcher 拿到剩余 - 15%,接近 8500ms 上下
    org.mockito.ArgumentCaptor<Duration> dispCap =
        org.mockito.ArgumentCaptor.forClass(Duration.class);
    verify(dispatcher).stop(dispCap.capture());
    long dispMs = dispCap.getValue().toMillis();
    // remainingMs (≈10000) - 15% (1500) = 8500;允许 ±500ms 因为前两步实际耗时极小
    assertThat(dispMs).isBetween(8_000L, 9_000L);
  }

  /** Lane E #5:总耗时不超 stop 总预算(allmocks 接近 0ms 返回 → 总耗时应远小于 timeout)。 */
  @Test
  void stopRespectsTotalTimeoutBudget() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg()).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    KafkaTaskConsumer kafka = mock(KafkaTaskConsumer.class);
    HeartbeatScheduler hb = mock(HeartbeatScheduler.class);
    LeaseRenewalScheduler lease = mock(LeaseRenewalScheduler.class);

    inject(client, "httpClient", http);
    inject(client, "dispatcher", dispatcher);
    inject(client, "kafkaConsumer", kafka);
    inject(client, "heartbeatScheduler", hb);
    inject(client, "leaseRenewalScheduler", lease);
    inject(client, "started", true);

    long t0 = System.nanoTime();
    client.stop(Duration.ofMillis(500));
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

    assertThat(elapsedMs).isLessThan(1_000L);
  }
}
