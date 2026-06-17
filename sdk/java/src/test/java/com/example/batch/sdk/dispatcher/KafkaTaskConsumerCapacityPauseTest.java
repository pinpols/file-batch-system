package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P0 hardening — 验证 {@link KafkaTaskConsumer#applyBackpressure()} 在 in-flight task 达到 {@code
 * maxConcurrentTasks} 时调用 {@code consumer.pause(...)},降下来后调 {@code resume(...)}。 防 worker 因 Kafka
 * consumer 持续 poll 把消息囤进内存 OOM(Zeebe maxJobsActive 模式)。
 */
class KafkaTaskConsumerCapacityPauseTest {

  private TaskDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    dispatcher = mock(TaskDispatcher.class);
  }

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

  private final TopicPartition tp = new TopicPartition("batch.task.dispatch.tx.t0", 0);

  private KafkaTaskConsumer newConsumer(MockConsumer<String, byte[]> mock) {
    return new KafkaTaskConsumer(config, dispatcher, mock, new ObjectMapper());
  }

  @Test
  void pausesAssignedPartitionsWhenInFlightAtCapacity() {
    when(dispatcher.submittedCount()).thenReturn(2); // == maxConcurrentTasks
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    when(dispatcher.platformState()).thenReturn(WorkerRuntimeState.NORMAL);
    MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
    mockConsumer.assign(List.of(tp));
    KafkaTaskConsumer consumer = newConsumer(mockConsumer);

    consumer.applyBackpressure();

    assertThat(mockConsumer.paused()).containsExactly(tp);
  }

  @Test
  void resumesAssignedPartitionsAfterInFlightDropsBelowCapacity() {
    when(dispatcher.submittedCount()).thenReturn(2, 0); // 第一次满,第二次空
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    when(dispatcher.platformState()).thenReturn(WorkerRuntimeState.NORMAL);
    MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
    mockConsumer.assign(List.of(tp));
    KafkaTaskConsumer consumer = newConsumer(mockConsumer);

    // first tick: pause
    consumer.applyBackpressure();
    assertThat(mockConsumer.paused()).containsExactly(tp);

    // second tick: in-flight drained -> resume
    consumer.applyBackpressure();
    assertThat(mockConsumer.paused()).isEmpty();
  }

  @Test
  void doesNotRepeatPauseWhileAlreadyPaused() {
    when(dispatcher.submittedCount()).thenReturn(5); // 持续满
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    when(dispatcher.platformState()).thenReturn(WorkerRuntimeState.NORMAL);
    MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
    mockConsumer.assign(List.of(tp));
    KafkaTaskConsumer consumer = newConsumer(mockConsumer);

    consumer.applyBackpressure();
    Set<TopicPartition> afterFirst = Set.copyOf(mockConsumer.paused());
    consumer.applyBackpressure();
    consumer.applyBackpressure();

    // paused 集仍是同一份(MockConsumer.pause 累加,但语义上 partition 集不变)
    assertThat(mockConsumer.paused()).isEqualTo(afterFirst);
  }

  @Test
  void doesNotPauseWhenInFlightBelowCapacity() {
    when(dispatcher.submittedCount()).thenReturn(1);
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
    mockConsumer.assign(List.of(tp));
    KafkaTaskConsumer consumer = newConsumer(mockConsumer);

    consumer.applyBackpressure();

    assertThat(mockConsumer.paused()).isEmpty();
  }

  /**
   * Round-3 #1 hysteresis:max=10 时 resume 阈值 = max/2 = 5; in-flight 从 10 跌到 6(>=5)不该 resume, 跌到
   * 4(<5)才 resume。防 max-1 / max 抖动反复颠簸 Kafka client。
   */
  @Test
  void keepsPausedUntilInFlightDropsBelowHalfMaxHysteresis() {
    BatchPlatformClientConfig bigConfig =
        BatchPlatformClientConfig.builder()
            .baseUrl("http://localhost:0")
            .tenantId("tx")
            .workerCode("w-1")
            .kafkaBootstrap("kafka:9092")
            .kafkaTopicPattern("batch.task.dispatch.tx.*")
            .kafkaGroupId("g")
            .maxConcurrentTasks(10)
            .build();
    when(dispatcher.submittedCount()).thenReturn(10, 6, 5, 4);
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    when(dispatcher.platformState()).thenReturn(WorkerRuntimeState.NORMAL);
    MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
    mockConsumer.assign(List.of(tp));
    KafkaTaskConsumer consumer =
        new KafkaTaskConsumer(bigConfig, dispatcher, mockConsumer, new ObjectMapper());

    // tick 1: inFlight=10 -> pause
    consumer.applyBackpressure();
    assertThat(mockConsumer.paused()).containsExactly(tp);
    // tick 2: inFlight=6, still >= max/2=5 -> 保持 paused
    consumer.applyBackpressure();
    assertThat(mockConsumer.paused()).containsExactly(tp);
    // tick 3: inFlight=5, 不 < 5 -> 仍保持 paused
    consumer.applyBackpressure();
    assertThat(mockConsumer.paused()).containsExactly(tp);
    // tick 4: inFlight=4 < 5 -> resume
    consumer.applyBackpressure();
    assertThat(mockConsumer.paused()).isEmpty();
  }

  @Test
  void capacityPauseSkippedWhenNoPartitionsAssigned() {
    when(dispatcher.submittedCount()).thenReturn(10);
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
    // 未 assign -> pause/resume 都跳过(避免 IllegalStateException)
    KafkaTaskConsumer consumer = newConsumer(mockConsumer);

    consumer.applyBackpressure();

    assertThat(mockConsumer.paused()).isEmpty();
  }
}
