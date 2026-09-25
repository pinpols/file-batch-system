package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.mq.MqMessage;
import io.github.pinpols.batch.common.mq.MqMessagePublisher;
import io.github.pinpols.batch.common.mq.MqPublishResult;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DeadLetterPublisherTest {

  @Mock
  private MqMessagePublisher mqMessagePublisher;

  private DeadLetterPublisher publisher;
  private MeterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    publisher = new DeadLetterPublisher(mqMessagePublisher, provider);
  }

  @Test
  void publish_sendsToDeadLetterTopic() {
    when(mqMessagePublisher.publish(any(MqMessage.class)))
        .thenReturn(CompletableFuture.completedFuture(MqPublishResult.acknowledged()));

    publisher.publish("payload", "batch.task.dispatch.import", "IMPORT", "some error");

    ArgumentCaptor<MqMessage> messageCaptor = ArgumentCaptor.forClass(MqMessage.class);
    verify(mqMessagePublisher).publish(messageCaptor.capture());

    MqMessage message = messageCaptor.getValue();
    assertThat(message.topic()).isEqualTo(BatchTopics.TASK_DEAD_LETTER);
    String sent = message.payload();
    assertThat(sent)
        .contains("\"envelopeVersion\":1")
        .contains("originalPayload")
        .contains("sourceTopic")
        .contains("workerType")
        .contains("errorMessage")
        .contains("failedAt");
    assertThat(registry
            .counter("worker.dlq.publish.success.total", "topic", BatchTopics.TASK_DEAD_LETTER)
            .count())
        .isEqualTo(1.0);
  }

  @Test
  void publish_longErrorMessage_truncatedTo2000chars() {
    when(mqMessagePublisher.publish(any(MqMessage.class)))
        .thenReturn(CompletableFuture.completedFuture(MqPublishResult.acknowledged()));

    String longError = "x".repeat(3000);
    publisher.publish("p", "t", "w", longError);

    ArgumentCaptor<MqMessage> captor = ArgumentCaptor.forClass(MqMessage.class);
    verify(mqMessagePublisher).publish(captor.capture());
    assertThat(captor.getValue().payload().length()).isLessThan(4000);
  }

  @Test
  void publish_nullErrorMessage_doesNotThrow() {
    when(mqMessagePublisher.publish(any(MqMessage.class)))
        .thenReturn(CompletableFuture.completedFuture(MqPublishResult.acknowledged()));

    assertThatCode(() -> publisher.publish("p", "t", "w", null)).doesNotThrowAnyException();
    verify(mqMessagePublisher).publish(any(MqMessage.class));
  }

  /** #4-3: DLQ 发送失败时应抛出异常，让调用方感知并决定是否提交偏移量. */
  @Test
  void publish_messagePublisherThrows_propagatesException() {
    doThrow(new RuntimeException("kafka down"))
        .when(mqMessagePublisher)
        .publish(any(MqMessage.class));

    assertThatThrownBy(() -> publisher.publish("p", "t", "w", "err"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("kafka down");
  }

  /**
   * P0-3: future 一直不完成 → 5s 后超时抛 IllegalStateException + timeout counter +1; 不再无限阻塞 listener 线程.
   */
  @Test
  void publish_brokerSlow_timesOutAndThrows() {
    // 永不完成的 future 模拟 broker 长期停滞
    CompletableFuture<MqPublishResult> stuck = new CompletableFuture<>();
    when(mqMessagePublisher.publish(any(MqMessage.class))).thenReturn(stuck);

    long start = BatchDateTimeSupport.utcEpochMillis();
    assertThatThrownBy(() -> publisher.publish("p", "t", "w", "err"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("timeout");
    long elapsed = BatchDateTimeSupport.utcEpochMillis() - start;

    // 必须在 5s ~ 10s 之间返回 (超时常量 5s + 调度抖动)
    assertThat(elapsed).isBetween(4500L, 10_000L);
    assertThat(registry
            .counter("worker.dlq.publish.timeout.total", "topic", BatchTopics.TASK_DEAD_LETTER)
            .count())
        .isEqualTo(1.0);
  }

  /** P0-3: future 完成但 ack 异常 → 失败 counter +1, 抛 IllegalStateException 保留 cause. */
  @Test
  void publish_ackFails_throwsAndRecordsFailureMetric() {
    CompletableFuture<MqPublishResult> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("broker rejected"));
    when(mqMessagePublisher.publish(any(MqMessage.class))).thenReturn(failed);

    assertThatThrownBy(() -> publisher.publish("p", "t", "w", "err"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("publish failed");
    assertThat(registry
            .counter("worker.dlq.publish.failed.total", "topic", BatchTopics.TASK_DEAD_LETTER)
            .count())
        .isEqualTo(1.0);
  }
}
