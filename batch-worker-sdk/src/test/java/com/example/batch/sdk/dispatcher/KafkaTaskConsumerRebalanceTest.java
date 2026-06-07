package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 §3.1 #1.3:Kafka rebalance 后 partition 级别的 pause 状态会丢失,SDK 必须在 {@code
 * ConsumerRebalanceListener.onPartitionsAssigned()} 重新 pause 新分到的 partition,否则 backpressure
 * 期间也会拉新消息绕过 maxConcurrent 上限。
 */
class KafkaTaskConsumerRebalanceTest {

  private final BatchPlatformClientConfig config =
      BatchPlatformClientConfig.builder()
          .baseUrl("http://localhost:0")
          .tenantId("tx")
          .workerCode("w-1")
          .kafkaBootstrap("kafka:9092")
          .kafkaTopicPattern("batch.task.dispatch.tx.*")
          .kafkaGroupId("g")
          .maxConcurrentTasks(2)
          .build();

  private TaskDispatcher dispatcher;
  private MockConsumer<String, byte[]> mockConsumer;

  @AfterEach
  void tearDown() {
    if (dispatcher != null) dispatcher.stop();
  }

  private TaskDispatcher newDispatcher() {
    return new TaskDispatcher(config, Map.of(), org.mockito.Mockito.mock(PlatformHttpClient.class));
  }

  @Test
  void onPartitionsAssignedRestoresPauseWhenBackpressureActive() {
    dispatcher = newDispatcher();
    mockConsumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, mockConsumer, new ObjectMapper());

    // 准备 — 模拟 backpressure 已激活(consumer 内部 paused=true,新 partition 进来要 re-pause)
    setPaused(kafka, true);
    TopicPartition tp0 = new TopicPartition("batch.task.dispatch.tx.t0", 0);
    TopicPartition tp1 = new TopicPartition("batch.task.dispatch.tx.t1", 0);

    // 执行 — 模拟 Kafka rebalance 给出新 partition
    KafkaTaskConsumer.PauseAwareRebalanceListener listener =
        kafka.new PauseAwareRebalanceListener();
    mockConsumer.assign(List.of(tp0, tp1));
    listener.onPartitionsAssigned(List.of(tp0, tp1));

    // 断言 — 新 partition 应该被 pause
    assertThat(mockConsumer.paused()).containsExactlyInAnyOrder(tp0, tp1);
  }

  @Test
  void onPartitionsAssignedDoesNotPauseWhenNoBackpressure() {
    dispatcher = newDispatcher();
    mockConsumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, mockConsumer, new ObjectMapper());

    // 正常路径:paused=false 时不应 pause 新 partition
    TopicPartition tp = new TopicPartition("batch.task.dispatch.tx.t0", 0);
    mockConsumer.assign(List.of(tp));

    KafkaTaskConsumer.PauseAwareRebalanceListener listener =
        kafka.new PauseAwareRebalanceListener();
    listener.onPartitionsAssigned(List.of(tp));

    assertThat(mockConsumer.paused()).isEmpty();
  }

  @Test
  void onPartitionsAssignedEmptyListIsNoop() {
    dispatcher = newDispatcher();
    mockConsumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, mockConsumer, new ObjectMapper());

    setPaused(kafka, true);

    KafkaTaskConsumer.PauseAwareRebalanceListener listener =
        kafka.new PauseAwareRebalanceListener();
    listener.onPartitionsAssigned(List.of());

    assertThat(mockConsumer.paused()).isEmpty();
  }

  private static void setPaused(KafkaTaskConsumer kafka, boolean v) {
    try {
      var f = KafkaTaskConsumer.class.getDeclaredField("paused");
      f.setAccessible(true);
      f.setBoolean(kafka, v);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
