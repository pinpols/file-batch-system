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
@DisplayName("KafkaTaskConsumer 容量背压 — 在途任务达上限时暂停分区,回落后续订")
class KafkaTaskConsumerCapacityPauseTest {

  private TaskDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    dispatcher = mock(TaskDispatcher.class);
  }

  private final BatchPlatformClientConfig config = BatchPlatformClientConfig.builder()
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
  @DisplayName("在途任务达到并发上限时暂停已分配分区,避免继续拉取消息")
  void shouldPauseAssignedPartitions_whenInFlightAtCapacity() {
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
  @DisplayName("在途任务回落到上限以下时恢复订阅已分配分区")
  void shouldResumeAssignedPartitions_whenInFlightDropsBelowCapacity() {
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
  @DisplayName("持续满负载下重复触发背压,暂停分区集合保持不变")
  void shouldNotRepeatPause_whenAlreadyPaused() {
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
  @DisplayName("在途任务未达上限时不暂停任何分区")
  void shouldNotPause_whenInFlightBelowCapacity() {
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
  @DisplayName("恢复阈值取并发上限一半,在途回落到半数以下才恢复订阅")
  void shouldKeepPaused_whenInFlightAtOrAboveHalfLimit() {
    BatchPlatformClientConfig bigConfig = BatchPlatformClientConfig.builder()
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

  /** withhold 不再 pause 分区;容量 pause/resume 仍必须完整覆盖 assignment。 */
  @Test
  @DisplayName("withhold ceiling 不干扰容量维度 pause/resume")
  void shouldCoverWithheldPartition_whenCapacityResumes() {
    TopicPartition withheld = new TopicPartition("batch.task.dispatch.tx.t0", 0);
    TopicPartition healthy = new TopicPartition("batch.task.dispatch.tx.t1", 0);
    // 容量先满(pause 整个 assignment),再跌破阈值(resume)
    when(dispatcher.submittedCount()).thenReturn(2, 0);
    when(dispatcher.platformAcceptsNewTasks()).thenReturn(true);
    when(dispatcher.platformState()).thenReturn(WorkerRuntimeState.NORMAL);
    try (MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>("latest")) {
      mockConsumer.assign(List.of(withheld, healthy));
      try (KafkaTaskConsumer consumer = newConsumer(mockConsumer)) {

        // 一条 v3 落到 t0 → WITHHOLD commit ceiling,继续消费且不 pause。
        byte[] v3 =
            ("{\"taskId\":42,\"tenantId\":\"tx\",\"jobCode\":\"job-1\",\"taskType\":\"task-type\","
                    + "\"taskInstanceId\":\"ti\",\"schemaVersion\":\"v3\"}")
                .getBytes(StandardCharsets.UTF_8);
        boolean keepGoing = consumer.handleRecordAndMaybeCommit(
            new ConsumerRecord<>("batch.task.dispatch.tx.t0", 0, 5, "k", v3));
        assertThat(keepGoing).isTrue();
        assertThat(mockConsumer.paused()).isEmpty();

        // act: 容量满 → pause 整个 assignment;再跌破 → resume
        consumer.applyBackpressure(); // pause
        assertThat(mockConsumer.paused()).contains(withheld, healthy);
        consumer.applyBackpressure(); // resume 全 assignment

        assertThat(mockConsumer.paused()).isEmpty();
      }
    }
  }

  @Test
  @DisplayName("未分配到任何分区时跳过暂停与恢复,不触发客户端异常")
  void shouldSkipPauseAndResume_whenNoPartitionsAssigned() {
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
