package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.time.BatchDateTimeSupport;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class DeadLetterPublisherTest {

  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private DeadLetterPublisher publisher;
  private MeterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    publisher = new DeadLetterPublisher(kafkaTemplate, provider);
  }

  @Test
  void publish_sendsToDeadLetterTopic() {
    when(kafkaTemplate.send(anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));

    publisher.publish("payload", "batch.task.dispatch.import", "IMPORT", "some error");

    ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
    verify(kafkaTemplate).send(eq(BatchTopics.TASK_DEAD_LETTER), valueCaptor.capture());

    String sent = valueCaptor.getValue();
    assertThat(sent).contains("\"envelopeVersion\":1");
    assertThat(sent).contains("originalPayload");
    assertThat(sent).contains("sourceTopic");
    assertThat(sent).contains("workerType");
    assertThat(sent).contains("errorMessage");
    assertThat(sent).contains("failedAt");
    assertThat(
            registry
                .counter("worker.dlq.publish.success.total", "topic", BatchTopics.TASK_DEAD_LETTER)
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void publish_longErrorMessage_truncatedTo2000chars() {
    when(kafkaTemplate.send(anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));

    String longError = "x".repeat(3000);
    publisher.publish("p", "t", "w", longError);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(kafkaTemplate).send(anyString(), captor.capture());
    assertThat(captor.getValue().length()).isLessThan(4000);
  }

  @Test
  void publish_nullErrorMessage_doesNotThrow() {
    when(kafkaTemplate.send(anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));

    assertThatCode(() -> publisher.publish("p", "t", "w", null)).doesNotThrowAnyException();
    verify(kafkaTemplate).send(anyString(), anyString());
  }

  /** #4-3: DLQ 发送失败时应抛出异常，让调用方感知并决定是否提交偏移量. */
  @Test
  void publish_kafkaTemplateThrows_propagatesException() {
    doThrow(new RuntimeException("kafka down")).when(kafkaTemplate).send(anyString(), anyString());

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
    CompletableFuture<SendResult<String, String>> stuck = new CompletableFuture<>();
    when(kafkaTemplate.send(anyString(), anyString())).thenReturn(stuck);

    long start = BatchDateTimeSupport.utcEpochMillis();
    assertThatThrownBy(() -> publisher.publish("p", "t", "w", "err"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("timeout");
    long elapsed = BatchDateTimeSupport.utcEpochMillis() - start;

    // 必须在 5s ~ 10s 之间返回 (超时常量 5s + 调度抖动)
    assertThat(elapsed).isBetween(4500L, 10_000L);
    assertThat(
            registry
                .counter("worker.dlq.publish.timeout.total", "topic", BatchTopics.TASK_DEAD_LETTER)
                .count())
        .isEqualTo(1.0);
  }

  /** P0-3: future 完成但 ack 异常 → 失败 counter +1, 抛 IllegalStateException 保留 cause. */
  @Test
  void publish_ackFails_throwsAndRecordsFailureMetric() {
    CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("broker rejected"));
    when(kafkaTemplate.send(anyString(), anyString())).thenReturn(failed);

    assertThatThrownBy(() -> publisher.publish("p", "t", "w", "err"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("publish failed");
    assertThat(
            registry
                .counter("worker.dlq.publish.failed.total", "topic", BatchTopics.TASK_DEAD_LETTER)
                .count())
        .isEqualTo(1.0);
  }
}
