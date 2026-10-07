package io.github.pinpols.batch.sdk.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.sdk.dispatcher.KafkaTaskConsumer;
import io.github.pinpols.batch.sdk.dispatcher.TaskDispatcher;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.scheduler.HeartbeatScheduler;
import io.github.pinpols.batch.sdk.scheduler.LeaseRenewalScheduler;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * Phase 1 §3.1 #1.1:验证 {@link BatchPlatformClient#stop()} 的关闭顺序 Kafka consumer → dispatcher drain →
 * cancel in-flight HTTP → heartbeat → lease → deactivate → evict idle connections。
 *
 * <p>正确顺序保护:drain 期间 heartbeat / lease 仍在跑维持租约,避免 orchestrator 在 worker 完成 in-flight 任务过程中误判 worker
 * 死了把同 task 派给别人。
 */
@DisplayName("BatchPlatformClient 停止顺序 — 关闭次序与注销异常兜底")
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
  @DisplayName("停止时先关消费端并排空派发,再取消在飞请求、关心跳与租约并注销")
  void shouldCloseInFixedOrder_whenStopping() throws Exception {
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
    order.verify(http).cancelInFlightCalls();
    order.verify(hb).close(any(Duration.class));
    order.verify(lease).close(any(Duration.class));
    order.verify(http).deactivate(anyString(), any(), any(Duration.class));
    order.verify(http).evictIdleConnections();
  }

  @Test
  @DisplayName("注销上报失败被吞掉,停止流程仍完整走完")
  void shouldSwallowDeactivateFailure_whenStopping() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg()).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    doThrow(new IOException("network down"))
        .when(http)
        .deactivate(anyString(), any(), any(Duration.class));

    inject(client, "httpClient", http);
    inject(client, "started", true);

    client.stop();

    verify(http).deactivate(anyString(), any(), any(Duration.class));
    verify(http).evictIdleConnections();
  }

  @Test
  @DisplayName("未启动时调用停止不对任何组件发起操作")
  void shouldDoNothing_whenStopCalledBeforeStart() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg()).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    inject(client, "httpClient", http);

    client.stop();

    Mockito.verifyNoInteractions(http);
  }

  @Test
  @DisplayName("启动中途失败时即使 started=false 也清理已分配的运行资源")
  void shouldCleanPartialRuntimeResources_whenStartupFailsBeforeStartedFlag() throws Exception {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg()).build();
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    KafkaTaskConsumer kafka = mock(KafkaTaskConsumer.class);
    HeartbeatScheduler heartbeat = mock(HeartbeatScheduler.class);
    LeaseRenewalScheduler lease = mock(LeaseRenewalScheduler.class);

    inject(client, "httpClient", http);
    inject(client, "started", false);
    inject(client, "dispatcher", dispatcher);
    inject(client, "kafkaConsumer", kafka);
    inject(client, "heartbeatScheduler", heartbeat);
    inject(client, "leaseRenewalScheduler", lease);

    client.stop(Duration.ofMillis(500));

    InOrder order = Mockito.inOrder(kafka, dispatcher, heartbeat, lease, http);
    order.verify(kafka).close(any(Duration.class));
    order.verify(dispatcher).stop(any(Duration.class));
    order.verify(http).cancelInFlightCalls();
    order.verify(heartbeat).close(any(Duration.class));
    order.verify(lease).close(any(Duration.class));
    order.verify(http).deactivate(anyString(), any(), any(Duration.class));
    order.verify(http).evictIdleConnections();
  }
}
