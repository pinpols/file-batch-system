package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Lane E #5:{@link KafkaTaskConsumer#close(Duration)} 必须 wakeup + join poll 线程, 超时不阻塞返回,且超时打 WARN
 * 提示 offset 可能未提交。
 */
class KafkaConsumerCloseJoinTest {

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
  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @AfterEach
  void tearDown() throws Exception {
    if (runner != null && runner.isAlive()) {
      runner.interrupt();
      runner.join(2_000);
    }
    if (dispatcher != null) dispatcher.stop();
    if (logger != null && appender != null) logger.detachAppender(appender);
  }

  @SuppressWarnings("unchecked")
  private Consumer<String, byte[]> mockConsumer() {
    return mock(Consumer.class);
  }

  private void attachCapture() {
    logger = (Logger) LoggerFactory.getLogger(KafkaTaskConsumer.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  private List<String> warnMessages() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  /** close(Duration) 在 join 超时时返回 + 打 WARN,且仍触发 wakeup;poll 线程未及时退出场景。 */
  @Test
  void closeReturnsWithinTimeoutAndWarnsWhenPollLoopStuck() throws Exception {
    attachCapture();
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    Consumer<String, byte[]> consumer = mockConsumer();
    doNothing().when(consumer).subscribe(any(Pattern.class), any(ConsumerRebalanceListener.class));
    when(consumer.assignment()).thenReturn(Set.of());
    doNothing().when(consumer).pause(anyCollection());
    doNothing().when(consumer).resume(anyCollection());
    // poll 长 sleep —— 模拟卡在 broker 不响应,wakeup 也"假装"被忽略(测 join 超时路径)
    doAnswer(
            inv -> {
              Thread.sleep(2_000);
              return ConsumerRecords.<String, byte[]>empty();
            })
        .when(consumer)
        .poll(any());
    doNothing().when(consumer).wakeup(); // 故意不联动让 poll 抛 — 测 join 超时

    try (KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper())) {
      runner = new Thread(kafka, "test-kafka-stuck");
      runner.setDaemon(true);
      runner.start();
      Thread.sleep(150); // 让 poll loop 进入 sleep

      long t0 = System.nanoTime();
      kafka.close(Duration.ofMillis(500));
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

      assertThat(elapsedMs)
          .as("close should return close to 500ms join timeout, not block on stuck poll")
          .isLessThan(1_200L);
      verify(consumer, atLeastOnce()).wakeup();
      assertThat(warnMessages())
          .anySatisfy(m -> assertThat(m).contains("did not exit within").contains("500"));
    }
  }

  /** close(Duration) 在 poll 线程已退出时立刻返回,不打 WARN。 */
  @Test
  void closeReturnsImmediatelyWhenPollLoopExits() throws Exception {
    attachCapture();
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    Consumer<String, byte[]> consumer = mockConsumer();
    doNothing().when(consumer).subscribe(any(Pattern.class), any(ConsumerRebalanceListener.class));
    when(consumer.assignment()).thenReturn(Set.of());
    doNothing().when(consumer).pause(anyCollection());
    doNothing().when(consumer).resume(anyCollection());
    // 正常 wakeup 联动 —— poll 抛 WakeupException 让 run() 退出
    doAnswer(
            inv -> {
              Thread.sleep(50);
              return ConsumerRecords.<String, byte[]>empty();
            })
        .when(consumer)
        .poll(any());
    doAnswer(
            inv -> {
              when(consumer.poll(any()))
                  .thenThrow(new org.apache.kafka.common.errors.WakeupException());
              return null;
            })
        .when(consumer)
        .wakeup();

    try (KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper())) {
      runner = new Thread(kafka, "test-kafka-clean");
      runner.setDaemon(true);
      runner.start();
      Thread.sleep(100);

      long t0 = System.nanoTime();
      kafka.close(Duration.ofMillis(1_000));
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

      assertThat(elapsedMs).as("clean exit should return quickly").isLessThan(800L);
      runner.join(1_000);
      assertThat(runner.isAlive()).isFalse();
      assertThat(warnMessages()).noneSatisfy(m -> assertThat(m).contains("did not exit within"));
    }
  }

  /** 二次调用 close() 幂等,不重复 wakeup / 不阻塞。 */
  @Test
  void closeIsIdempotent() throws Exception {
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    Consumer<String, byte[]> consumer = mockConsumer();
    doNothing().when(consumer).subscribe(any(Pattern.class), any(ConsumerRebalanceListener.class));
    when(consumer.assignment()).thenReturn(Set.of());
    try (KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper())) {
      // 不启动 run 线程 — close 也应直接返回
      kafka.close(Duration.ofMillis(100));
      kafka.close(Duration.ofMillis(100));
      assertThat(kafka.isRunning()).isFalse();
    }
  }
}
