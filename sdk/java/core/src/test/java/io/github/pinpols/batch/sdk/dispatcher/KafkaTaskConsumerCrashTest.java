package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 §3.1 #1.7:Kafka poll loop 若因非预期 Throwable 退出,必须置 {@code crashed=true},不能静默死。 这样 {@link
 * io.github.pinpols.batch.sdk.client.BatchPlatformClient#isHealthy()} 才能正确返回 false 让运维介入。
 */
class KafkaTaskConsumerCrashTest {

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
  private Thread runner;

  @AfterEach
  void tearDown() throws Exception {
    if (runner != null && runner.isAlive()) {
      runner.interrupt();
      runner.join(2_000);
    }
    if (dispatcher != null) dispatcher.stop();
  }

  @SuppressWarnings("unchecked")
  private Consumer<String, byte[]> mockConsumer() {
    return mock(Consumer.class);
  }

  @Test
  void crashedFlagSetWhenPollLoopThrowsUnexpected() throws Exception {
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    Consumer<String, byte[]> consumer = mockConsumer();
    doNothing().when(consumer).subscribe(any(Pattern.class), any(ConsumerRebalanceListener.class));
    when(consumer.assignment()).thenReturn(Set.of());
    // 非 WakeupException 的 Throwable — 模拟 broker 端非预期断开
    when(consumer.poll(any())).thenThrow(new RuntimeException("simulated broker failure"));

    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper());
    runner = new Thread(kafka, "test-kafka-crash");
    runner.setDaemon(true);
    runner.start();

    runner.join(3_000);
    assertThat(kafka.hasCrashed()).isTrue();
    assertThat(kafka.isRunning()).isFalse();
  }

  @Test
  void wakeupExceptionFromCloseIsNotCrash() throws Exception {
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    Consumer<String, byte[]> consumer = mockConsumer();
    doNothing().when(consumer).subscribe(any(Pattern.class), any(ConsumerRebalanceListener.class));
    when(consumer.assignment()).thenReturn(Set.of());
    // close() 主动触发 WakeupException — 正常停,不是 crash
    when(consumer.poll(any())).thenThrow(new WakeupException());

    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper());
    runner = new Thread(kafka, "test-kafka-wakeup");
    runner.setDaemon(true);
    runner.start();
    runner.join(3_000);

    assertThat(kafka.hasCrashed()).isFalse();
  }

  @Test
  void normalCloseDoesNotCrash() throws Exception {
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    Consumer<String, byte[]> consumer = mockConsumer();
    doNothing().when(consumer).subscribe(any(Pattern.class), any(ConsumerRebalanceListener.class));
    when(consumer.assignment()).thenReturn(Set.of());
    // poll 阻塞短时,close() 调 wakeup → 第二次 poll 抛 WakeupException
    doAnswer(
            inv -> {
              Thread.sleep(50);
              return org.apache.kafka.clients.consumer.ConsumerRecords.<String, byte[]>empty();
            })
        .when(consumer)
        .poll(any());
    doAnswer(
            inv -> {
              when(consumer.poll(any())).thenThrow(new WakeupException());
              return null;
            })
        .when(consumer)
        .wakeup();
    doNothing().when(consumer).resume(anyCollection());
    doNothing().when(consumer).pause(anyCollection());

    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper());
    runner = new Thread(kafka, "test-kafka-normal");
    runner.setDaemon(true);
    runner.start();
    Thread.sleep(100);
    kafka.close();
    runner.join(3_000);

    assertThat(kafka.hasCrashed()).isFalse();
    assertThat(kafka.isRunning()).isFalse();
  }
}
