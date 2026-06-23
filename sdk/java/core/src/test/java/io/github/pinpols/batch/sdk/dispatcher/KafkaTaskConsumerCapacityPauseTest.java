package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    try (MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>("latest")) {
      mockConsumer.assign(List.of(tp));
      try (KafkaTaskConsumer consumer = newConsumer(mockConsumer)) {

        consumer.applyBackpressure();

        assertThat(mockConsumer.paused()).containsExactly(tp);
      }
    }
  }

  @Test
  void resumesAssignedPartitionsAfterInFlightDropsBelowCapacity() {
    when(dispatcher.submittedCount()).thenReturn(2, 0); // 第一次满,第二次空
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    when(dispatcher.platformState()).thenReturn(WorkerRuntimeState.NORMAL);
    try (MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>("latest")) {
      mockConsumer.assign(List.of(tp));
      try (KafkaTaskConsumer consumer = newConsumer(mockConsumer)) {

        // first tick: pause
        consumer.applyBackpressure();
        assertThat(mockConsumer.paused()).containsExactly(tp);

        // second tick: in-flight drained -> resume
        consumer.applyBackpressure();
        assertThat(mockConsumer.paused()).isEmpty();
      }
    }
  }

  @Test
  void doesNotRepeatPauseWhileAlreadyPaused() {
    when(dispatcher.submittedCount()).thenReturn(5); // 持续满
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    when(dispatcher.platformState()).thenReturn(WorkerRuntimeState.NORMAL);
    try (MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>("latest")) {
      mockConsumer.assign(List.of(tp));
      try (KafkaTaskConsumer consumer = newConsumer(mockConsumer)) {

        consumer.applyBackpressure();
        Set<TopicPartition> afterFirst = Set.copyOf(mockConsumer.paused());
        consumer.applyBackpressure();
        consumer.applyBackpressure();

        // paused 集仍是同一份(MockConsumer.pause 累加,但语义上 partition 集不变)
        assertThat(mockConsumer.paused()).isEqualTo(afterFirst);
      }
    }
  }

  @Test
  void doesNotPauseWhenInFlightBelowCapacity() {
    when(dispatcher.submittedCount()).thenReturn(1);
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    try (MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>("latest")) {
      mockConsumer.assign(List.of(tp));
      try (KafkaTaskConsumer consumer = newConsumer(mockConsumer)) {

        consumer.applyBackpressure();

        assertThat(mockConsumer.paused()).isEmpty();
      }
    }
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
    try (MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>("latest")) {
      mockConsumer.assign(List.of(tp));
      try (KafkaTaskConsumer consumer =
          new KafkaTaskConsumer(bigConfig, dispatcher, mockConsumer, new ObjectMapper())) {

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
    }
  }

  /**
   * #9 回归:一条 poison(未知 schema v3)记录被 RETRY_LATER seek+pause 该分区后,容量维度的 pause→resume 周期 **不得** 把这个
   * poison 分区一起 resume —— 否则下一轮 poll 会重读被 seek 的 poison 记录再 RETRY_LATER,形成忙旋转。poison 分区维持 HOL
   * 暂停,只有非 poison 分区随容量 resume。
   */
  @Test
  @DisplayName("#9:容量 resume 不放开 poison(RETRY_LATER)分区,避免重读 poison 记录忙旋转")
  void capacityResumeDoesNotResumePoisonPausedPartition() {
    TopicPartition poison = new TopicPartition("batch.task.dispatch.tx.t0", 0);
    TopicPartition healthy = new TopicPartition("batch.task.dispatch.tx.t1", 0);
    // 容量先满(pause 整个 assignment),再跌破阈值(resume)
    when(dispatcher.submittedCount()).thenReturn(2, 0);
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    when(dispatcher.platformState()).thenReturn(WorkerRuntimeState.NORMAL);
    try (MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>("latest")) {
      mockConsumer.assign(List.of(poison, healthy));
      try (KafkaTaskConsumer consumer = newConsumer(mockConsumer)) {

        // arrange: 一条 v3 poison 记录落到 t0 → RETRY_LATER → seek+pause + 记入 poison 集
        byte[] v3 =
            ("{\"taskId\":42,\"tenantId\":\"tx\",\"jobCode\":\"job-1\",\"taskType\":\"task-type\","
                    + "\"taskInstanceId\":\"ti\",\"schemaVersion\":\"v3\"}")
                .getBytes(StandardCharsets.UTF_8);
        boolean keepGoing =
            consumer.handleRecordAndMaybeCommit(
                new ConsumerRecord<>("batch.task.dispatch.tx.t0", 0, 5, "k", v3));
        assertThat(keepGoing).isFalse();
        assertThat(mockConsumer.paused()).contains(poison);

        // act: 容量满 → pause 整个 assignment;再跌破 → resume
        consumer.applyBackpressure(); // pause
        assertThat(mockConsumer.paused()).contains(poison, healthy);
        consumer.applyBackpressure(); // resume 仅非 poison

        // assert: poison 分区仍 paused,healthy 分区已 resume
        assertThat(mockConsumer.paused()).containsExactly(poison);
      }
    }
  }

  @Test
  void capacityPauseSkippedWhenNoPartitionsAssigned() {
    when(dispatcher.submittedCount()).thenReturn(10);
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    try (MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>("latest")) {
      // 未 assign -> pause/resume 都跳过(避免 IllegalStateException)
      try (KafkaTaskConsumer consumer = newConsumer(mockConsumer)) {

        consumer.applyBackpressure();

        assertThat(mockConsumer.paused()).isEmpty();
      }
    }
  }
}
