package com.example.batch.sdk.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.batch.sdk.dispatcher.KafkaTaskConsumer;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.scheduler.HeartbeatScheduler;
import com.example.batch.sdk.scheduler.LeaseRenewalScheduler;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * Phase 1 §3.1 #1.1:验证 {@link BatchPlatformClient#stop()} 的关闭顺序 Kafka consumer → dispatcher drain →
 * heartbeat → lease → deactivate。
 *
 * <p>正确顺序保护:drain 期间 heartbeat / lease 仍在跑维持租约,避免 orchestrator 在 worker 完成 in-flight 任务过程中误判 worker
 * 死了把同 task 派给别人。
 */
class BatchPlatformClientStopOrderTest {

  private static BatchPlatformClientConfig cfg() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("https://batch.example.com")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.tx.*")
        .kafkaGroupId("g")
        .build();
  }

  private static void inject(BatchPlatformClient target, String fieldName, Object value)
      throws Exception {
    Field f = BatchPlatformClient.class.getDeclaredField(fieldName);
    f.setAccessible(true);
    f.set(target, value);
  }

  @Test
  void stopClosesKafkaThenDispatcherThenSchedulersThenDeactivate() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg()).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    KafkaTaskConsumer kafka = mock(KafkaTaskConsumer.class);
    HeartbeatScheduler hb = mock(HeartbeatScheduler.class);
    LeaseRenewalScheduler lease = mock(LeaseRenewalScheduler.class);
    Thread kafkaThread = new Thread(() -> {}, "test-kafka");
    kafkaThread.start();
    kafkaThread.join();

    inject(client, "httpClient", http);
    inject(client, "started", true);
    inject(client, "dispatcher", dispatcher);
    inject(client, "kafkaConsumer", kafka);
    inject(client, "kafkaConsumerThread", kafkaThread);
    inject(client, "heartbeatScheduler", hb);
    inject(client, "leaseRenewalScheduler", lease);

    client.stop();

    InOrder order = Mockito.inOrder(kafka, dispatcher, hb, lease, http);
    order.verify(kafka).close(any(Duration.class));
    order.verify(dispatcher).stop(any(Duration.class));
    order.verify(hb).close();
    order.verify(lease).close();
    order.verify(http).deactivate(anyString(), any());
  }

  @Test
  void deactivateFailureSwallowedSoStopAlwaysFinishes() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg()).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    Mockito.doThrow(new IOException("network down")).when(http).deactivate(anyString(), any());

    inject(client, "httpClient", http);
    inject(client, "started", true);

    client.stop();

    verify(http).deactivate(anyString(), any());
  }

  @Test
  void stopWhenNotStartedIsNoop() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg()).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    inject(client, "httpClient", http);

    client.stop();

    Mockito.verifyNoInteractions(http);
  }
}
