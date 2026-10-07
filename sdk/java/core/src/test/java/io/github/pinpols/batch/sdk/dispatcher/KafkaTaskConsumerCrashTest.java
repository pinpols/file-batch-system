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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 §3.1 #1.7:Kafka poll loop 若因非预期 Throwable 退出,必须置 {@code crashed=true},不能静默死。 这样 {@link
 * io.github.pinpols.batch.sdk.client.BatchPlatformClient#isHealthy()} 才能正确返回 false 让运维介入。
 */
@DisplayName("Kafka 消费线程崩溃 — 非预期异常置崩溃标记并向上抛出")
class KafkaTaskConsumerCrashTest {

  private final BatchPlatformClientConfig config = BatchPlatformClientConfig.builder()
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
  @DisplayName("消费循环遇到非预期异常时置崩溃标记并停止运行")
  void shouldMarkCrashed_whenPollLoopThrowsUnexpected() throws Exception {
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
  @DisplayName("严重错误先记录崩溃状态再原样抛出,交由线程终止处理")
  void shouldRethrowFatalError_afterCrashStateRecorded() throws Exception {
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    Consumer<String, byte[]> consumer = mockConsumer();
    doNothing().when(consumer).subscribe(any(Pattern.class), any(ConsumerRebalanceListener.class));
    when(consumer.assignment()).thenReturn(Set.of());
    var fatal = new AssertionError("fatal kafka failure");
    when(consumer.poll(any())).thenThrow(fatal);

    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper());
    AtomicReference<Throwable> uncaught = new AtomicReference<>();
    runner = new Thread(kafka, "test-kafka-fatal");
    runner.setUncaughtExceptionHandler((thread, throwable) -> uncaught.set(throwable));
    runner.start();
    runner.join(3_000);

    assertThat(kafka.hasCrashed()).isTrue();
    assertThat(kafka.isRunning()).isFalse();
    assertThat(uncaught.get()).isSameAs(fatal);
  }

  @Test
  @DisplayName("关闭触发的唤醒异常按正常结束处理,不判为崩溃")
  void shouldNotMarkCrashed_whenCloseTriggersWakeup() throws Exception {
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
  @DisplayName("正常关闭后运行标记为假且不产生崩溃标记")
  void shouldNotMarkCrashed_whenClosedNormally() throws Exception {
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    Consumer<String, byte[]> consumer = mockConsumer();
    doNothing().when(consumer).subscribe(any(Pattern.class), any(ConsumerRebalanceListener.class));
    when(consumer.assignment()).thenReturn(Set.of());
    // poll 阻塞短时,close() 调 wakeup → 第二次 poll 抛 WakeupException
    doAnswer(inv -> {
          Thread.sleep(50);
          return org.apache.kafka.clients.consumer.ConsumerRecords.<String, byte[]>empty();
        })
        .when(consumer)
        .poll(any());
    doAnswer(inv -> {
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
