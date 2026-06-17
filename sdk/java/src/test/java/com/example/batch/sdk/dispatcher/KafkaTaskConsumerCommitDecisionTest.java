package com.example.batch.sdk.dispatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper());

    kafka.handleRecordAndMaybeCommit(record(7, message("tx")));

    verify(consumer)
        .commitSync(
            Map.of(new TopicPartition("batch.task.dispatch.tx.t0", 0), new OffsetAndMetadata(8)));
  }

  @Test
  void retryLaterDoesNotCommitOffsetAndSeeksBack() throws Exception {
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    when(dispatcher.onMessage(any())).thenReturn(TaskDispatcher.DispatchDecision.RETRY_LATER);
    @SuppressWarnings("unchecked")
    Consumer<String, byte[]> consumer = mock(Consumer.class);
    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper());

    kafka.handleRecordAndMaybeCommit(record(11, message("tx")));

    TopicPartition tp = new TopicPartition("batch.task.dispatch.tx.t0", 0);
    verify(consumer, never()).commitSync(any(Map.class));
    verify(consumer).seek(tp, 11);
    verify(consumer).pause(Set.of(tp));
  }

  @Test
  void terminalBadMessageCommitsOffset() {
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    @SuppressWarnings("unchecked")
    Consumer<String, byte[]> consumer = mock(Consumer.class);
    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper());

    kafka.handleRecordAndMaybeCommit(
        new ConsumerRecord<>(
            "batch.task.dispatch.tx.t0", 0, 3, "k", "not-json".getBytes(StandardCharsets.UTF_8)));

    verify(dispatcher, never()).onMessage(any());
    verify(consumer)
        .commitSync(
            Map.of(new TopicPartition("batch.task.dispatch.tx.t0", 0), new OffsetAndMetadata(4)));
  }

  @Test
  void unsupportedSchemaWithholdsOffsetAndSeeksBack() {
    // wire-protocol §A:未知大版本(v3)**不提交** offset(RETRY_LATER → seek+pause),
    // 而非 DROP_TERMINAL 静默跳过。schema 校验在 dispatcher.onMessage 之前拦截。
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    @SuppressWarnings("unchecked")
    Consumer<String, byte[]> consumer = mock(Consumer.class);
    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper());

    byte[] v3 =
        ("{\"taskId\":42,\"tenantId\":\"tx\",\"jobCode\":\"job-1\",\"taskType\":\"task-type\","
                + "\"taskInstanceId\":\"ti\",\"schemaVersion\":\"v3\"}")
            .getBytes(StandardCharsets.UTF_8);

    kafka.handleRecordAndMaybeCommit(
        new ConsumerRecord<>("batch.task.dispatch.tx.t0", 0, 5, "k", v3));

    TopicPartition tp = new TopicPartition("batch.task.dispatch.tx.t0", 0);
    verify(dispatcher, never()).onMessage(any());
    verify(consumer, never()).commitSync(any(Map.class));
    verify(consumer).seek(tp, 5);
    verify(consumer).pause(Set.of(tp));
  }

  private static ConsumerRecord<String, byte[]> record(long offset, TaskDispatchMessage msg)
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    return new ConsumerRecord<>(
        "batch.task.dispatch.tx.t0", 0, offset, "k", mapper.writeValueAsBytes(msg));
  }

  private static TaskDispatchMessage message(String tenantId) {
    return new TaskDispatchMessage(
        42L, tenantId, "job-1", "task-type", "task-instance-1", Map.of(), Map.of());
  }
}
