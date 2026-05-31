package com.example.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.dispatcher.KafkaTaskConsumer;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 §3.1 #1.6:验证 {@link BatchPlatformClient#metrics()} POJO 与 {@link
 * BatchPlatformClient#isHealthy()} 的语义。
 *
 * <p>因 BatchPlatformClient 内部字段在 start() 才被赋值,这里用反射注入 mock 避免真的拉 Kafka / orchestrator。
 */
class BatchPlatformClientMetricsTest {

  private static BatchPlatformClientConfig cfg() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("https://batch.example.com")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.tx.*")
        .kafkaGroupId("g")
        .maxConcurrentTasks(8)
        .build();
  }

  private static SdkTaskHandler stub(String type) {
    return new SdkTaskHandler() {
      @Override
      public String taskType() {
        return type;
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        return SdkTaskResult.ok();
      }
    };
  }

  private static void inject(BatchPlatformClient target, String field, Object value)
      throws Exception {
    Field f = BatchPlatformClient.class.getDeclaredField(field);
    f.setAccessible(true);
    f.set(target, value);
  }

  @Test
  void notStartedClientIsUnhealthyAndMetricsReflectIt() {
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg())
            .register(stub("type-a"))
            .register(stub("type-b"))
            .build();

    assertThat(client.isHealthy()).isFalse();
    SdkClientMetrics m = client.metrics();
    assertThat(m.tenantId()).isEqualTo("tx");
    assertThat(m.workerCode()).isEqualTo("w-1");
    assertThat(m.started()).isFalse();
    assertThat(m.healthy()).isFalse();
    assertThat(m.maxConcurrentTasks()).isEqualTo(8);
    assertThat(m.registeredHandlerCount()).isEqualTo(2);
    assertThat(m.inFlightTaskCount()).isZero();
    assertThat(m.dispatcherFatal()).isFalse();
    assertThat(m.dispatcherDraining()).isFalse();
    assertThat(m.consumerCrashed()).isFalse();
  }

  @Test
  void startedClientWithoutFatalOrCrashIsHealthy() throws Exception {
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg()).register(stub("type-a")).build();
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    KafkaTaskConsumer consumer = mock(KafkaTaskConsumer.class);
    when(dispatcher.isFatal()).thenReturn(false);
    when(dispatcher.isDraining()).thenReturn(false);
    when(dispatcher.inFlightCount()).thenReturn(3);
    when(consumer.hasCrashed()).thenReturn(false);

    inject(client, "started", true);
    inject(client, "dispatcher", dispatcher);
    inject(client, "kafkaConsumer", consumer);

    assertThat(client.isHealthy()).isTrue();
    SdkClientMetrics m = client.metrics();
    assertThat(m.started()).isTrue();
    assertThat(m.healthy()).isTrue();
    assertThat(m.inFlightTaskCount()).isEqualTo(3);
  }

  @Test
  void dispatcherFatalMakesClientUnhealthy() throws Exception {
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg()).register(stub("type-a")).build();
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.isFatal()).thenReturn(true);
    when(dispatcher.isDraining()).thenReturn(false);
    inject(client, "started", true);
    inject(client, "dispatcher", dispatcher);

    assertThat(client.isHealthy()).isFalse();
    SdkClientMetrics m = client.metrics();
    assertThat(m.dispatcherFatal()).isTrue();
    assertThat(m.healthy()).isFalse();
  }

  @Test
  void consumerCrashedMakesClientUnhealthy() throws Exception {
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg()).register(stub("type-a")).build();
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    KafkaTaskConsumer consumer = mock(KafkaTaskConsumer.class);
    when(dispatcher.isFatal()).thenReturn(false);
    when(consumer.hasCrashed()).thenReturn(true);
    inject(client, "started", true);
    inject(client, "dispatcher", dispatcher);
    inject(client, "kafkaConsumer", consumer);

    assertThat(client.isHealthy()).isFalse();
    SdkClientMetrics m = client.metrics();
    assertThat(m.consumerCrashed()).isTrue();
    assertThat(m.healthy()).isFalse();
  }

  @Test
  void drainingClientStillHealthy() throws Exception {
    // drain 是 graceful 状态:Kafka 不再消费但 lease / heartbeat 仍续约,平台不应误判
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg()).register(stub("type-a")).build();
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.isFatal()).thenReturn(false);
    when(dispatcher.isDraining()).thenReturn(true);
    inject(client, "started", true);
    inject(client, "dispatcher", dispatcher);

    assertThat(client.isHealthy()).isTrue();
    SdkClientMetrics m = client.metrics();
    assertThat(m.dispatcherDraining()).isTrue();
    assertThat(m.healthy()).isTrue();
  }
}
