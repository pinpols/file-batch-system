package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/** SDK Kafka offset 契约:只有已接收/终态坏消息提交 offset,可恢复拒收不提交。 */
class KafkaTaskConsumerCommitDecisionTest {

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

  @Test
  void commitsOffsetAfterDispatcherSubmitted() throws Exception {
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.onMessage(any())).thenReturn(TaskDispatcher.DispatchDecision.SUBMITTED);
    @SuppressWarnings("unchecked")
    Consumer<String, byte[]> consumer = mock(Consumer.class);
    try (KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper())) {
      kafka.handleRecordAndMaybeCommit(record(7, message("tx")));
    }

    verify(consumer)
        .commitSync(
            Map.of(new TopicPartition("batch.task.dispatch.tx.t0", 0), new OffsetAndMetadata(8)));
  }

  @Test
  void retryLaterDoesNotCommitOffsetAndSeeksBack() throws Exception {
    // RETRY_LATER = 瞬时背压(容量满 / 平台 PAUSED / draining):seek 回本条 + pause 分区(可恢复,由
    // applyBackpressure 在恢复后 resume)。这不是 withhold —— withhold 不 seek 不 pause(见下方 schema 测)。
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.onMessage(any())).thenReturn(TaskDispatcher.DispatchDecision.RETRY_LATER);
    @SuppressWarnings("unchecked")
    Consumer<String, byte[]> consumer = mock(Consumer.class);
    boolean keepGoing;
    try (KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper())) {
      keepGoing = kafka.handleRecordAndMaybeCommit(record(11, message("tx")));
    }

    TopicPartition tp = new TopicPartition("batch.task.dispatch.tx.t0", 0);
    assertThat(keepGoing).as("RETRY_LATER defers the current partition").isFalse();
    verify(consumer, never())
        .commitSync(org.mockito.ArgumentMatchers.<Map<TopicPartition, OffsetAndMetadata>>any());
    verify(consumer).seek(tp, 11);
    verify(consumer).pause(Set.of(tp));
  }

  @Test
  void retryLaterSkipsOnlyCurrentPartitionAndProcessesOtherPartitions() throws Exception {
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.onMessage(any()))
        .thenReturn(
            TaskDispatcher.DispatchDecision.RETRY_LATER, TaskDispatcher.DispatchDecision.SUBMITTED);
    @SuppressWarnings("unchecked")
    Consumer<String, byte[]> consumer = mock(Consumer.class);
    TopicPartition deferred = new TopicPartition("batch.task.dispatch.tx.t0", 0);
    TopicPartition healthy = new TopicPartition("batch.task.dispatch.tx.t1", 0);

    try (KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper())) {
      kafka.processPolledRecords(
          List.of(
              record(deferred.topic(), 5, message("tx")),
              record(deferred.topic(), 6, message("tx")),
              record(healthy.topic(), 7, message("tx"))));
    }

    // t0 的 offset 6 被留给 seek(5)后的重投;同一 poll 中的 t1 仍正常处理并提交。
    verify(dispatcher, times(2)).onMessage(any());
    verify(consumer).seek(deferred, 5);
    verify(consumer).pause(Set.of(deferred));
    verify(consumer).commitSync(Map.of(healthy, new OffsetAndMetadata(8)));
  }

  @Test
  void commitFailureSeeksBackAndDoesNotBlockOtherPartitions() throws Exception {
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.onMessage(any())).thenReturn(TaskDispatcher.DispatchDecision.SUBMITTED);
    @SuppressWarnings("unchecked")
    Consumer<String, byte[]> consumer = mock(Consumer.class);
    doThrow(new IllegalStateException("broker timeout"))
        .doNothing()
        .when(consumer)
        .commitSync(org.mockito.ArgumentMatchers.anyMap());
    TopicPartition failed = new TopicPartition("batch.task.dispatch.tx.t0", 0);
    TopicPartition healthy = new TopicPartition("batch.task.dispatch.tx.t1", 0);

    try (KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper())) {
      kafka.processPolledRecords(
          List.of(
              record(failed.topic(), 5, message("tx")),
              record(failed.topic(), 6, message("tx")),
              record(healthy.topic(), 7, message("tx"))));
    }

    verify(dispatcher, times(2)).onMessage(any());
    verify(consumer).seek(failed, 5);
    verify(consumer).pause(Set.of(failed));
    verify(consumer).commitSync(Map.of(healthy, new OffsetAndMetadata(8)));
  }

  @Test
  void terminalBadMessageCommitsOffset() {
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    @SuppressWarnings("unchecked")
    Consumer<String, byte[]> consumer = mock(Consumer.class);
    try (KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper())) {
      kafka.handleRecordAndMaybeCommit(
          new ConsumerRecord<>(
              "batch.task.dispatch.tx.t0", 0, 3, "k", "not-json".getBytes(StandardCharsets.UTF_8)));
    }

    verify(dispatcher, never()).onMessage(any());
    verify(consumer)
        .commitSync(
            Map.of(new TopicPartition("batch.task.dispatch.tx.t0", 0), new OffsetAndMetadata(4)));
  }

  @Test
  void unsupportedSchemaSetsCeilingAndDoesNotBlockLaterRecord() throws Exception {
    // wire-protocol §A:未知大版本(v3)**不提交** offset,但也不 seek/pause 冻结分区。
    // 后续正常记录继续送达 dispatcher,只是其 commit 被最低 withheld ceiling 拦住。
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.onMessage(any())).thenReturn(TaskDispatcher.DispatchDecision.SUBMITTED);
    @SuppressWarnings("unchecked")
    Consumer<String, byte[]> consumer = mock(Consumer.class);
    byte[] v3 =
        ("{\"taskId\":42,\"tenantId\":\"tx\",\"jobCode\":\"job-1\",\"taskType\":\"task-type\","
                + "\"taskInstanceId\":\"ti\",\"schemaVersion\":\"v3\"}")
            .getBytes(StandardCharsets.UTF_8);

    boolean withholdKeepsGoing;
    boolean laterKeepsGoing;
    try (KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper())) {
      withholdKeepsGoing =
          kafka.handleRecordAndMaybeCommit(
              new ConsumerRecord<>("batch.task.dispatch.tx.t0", 0, 5, "k", v3));
      laterKeepsGoing = kafka.handleRecordAndMaybeCommit(record(6, message("tx")));
    }

    TopicPartition tp = new TopicPartition("batch.task.dispatch.tx.t0", 0);
    assertThat(withholdKeepsGoing).isTrue();
    assertThat(laterKeepsGoing).isTrue();
    verify(dispatcher).onMessage(any());
    verify(consumer, never())
        .commitSync(org.mockito.ArgumentMatchers.<Map<TopicPartition, OffsetAndMetadata>>any());
    verify(consumer, never()).seek(tp, 5);
    verify(consumer, never()).pause(Set.of(tp));
  }

  private static ConsumerRecord<String, byte[]> record(long offset, TaskDispatchMessage msg)
      throws Exception {
    return record("batch.task.dispatch.tx.t0", offset, msg);
  }

  private static ConsumerRecord<String, byte[]> record(
      String topic, long offset, TaskDispatchMessage msg) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    return new ConsumerRecord<>(topic, 0, offset, "k", mapper.writeValueAsBytes(msg));
  }

  private static TaskDispatchMessage message(String tenantId) {
    return new TaskDispatchMessage(
        42L, tenantId, "job-1", "task-type", "task-instance-1", Map.of(), Map.of());
  }
}
