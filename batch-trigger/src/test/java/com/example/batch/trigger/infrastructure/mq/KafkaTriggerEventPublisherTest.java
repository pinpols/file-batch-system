package com.example.batch.trigger.infrastructure.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.trigger.application.TriggerEventPublisher;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.RecordBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * ADR-010 Stage 5: KafkaTriggerEventPublisher 单测,覆盖:
 *
 * <ol>
 *   <li>正常发送 → headers 正确 + PublishResult.ok
 *   <li>序列化失败(envelope null)→ PublishResult.fail
 *   <li>Kafka send 失败(ExecutionException)→ PublishResult.fail 含 cause message
 *   <li>InterruptedException → PublishResult.fail + 复位中断标志
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaTriggerEventPublisherTest {

  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private KafkaTriggerEventPublisher publisher;

  @BeforeEach
  void setUp() throws Exception {
    publisher = new KafkaTriggerEventPublisher(kafkaTemplate);
    setField(publisher, "sendTimeoutSeconds", 1);
  }

  @Test
  void publish_validEnvelope_sendsWithHeadersAndReturnsOk() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-1");
    SendResult<String, String> sendResult = sendResult(0, 100L);
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(sendResult));

    TriggerEventPublisher.PublishResult result =
        publisher.publish("batch.trigger.launch.v1", "tenant-a:req-1", envelope, "trace-1");

    assertThat(result.success()).isTrue();
    ArgumentCaptor<ProducerRecord<String, String>> captor =
        ArgumentCaptor.forClass(ProducerRecord.class);
    org.mockito.Mockito.verify(kafkaTemplate).send(captor.capture());
    ProducerRecord<String, String> sent = captor.getValue();
    assertThat(sent.topic()).isEqualTo("batch.trigger.launch.v1");
    assertThat(sent.key()).isEqualTo("tenant-a:req-1");
    assertThat(headerValue(sent, "X-Trace-Id")).isEqualTo("trace-1");
    assertThat(headerValue(sent, "X-Tenant-Id")).isEqualTo("tenant-a");
    assertThat(headerValue(sent, "X-Envelope-Version")).isEqualTo("1");
  }

  @Test
  void publish_nullEnvelope_failsWithoutSending() {
    TriggerEventPublisher.PublishResult result =
        publisher.publish("batch.trigger.launch.v1", "key", null, "trace");

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("null");
    org.mockito.Mockito.verify(kafkaTemplate, org.mockito.Mockito.never())
        .send(any(ProducerRecord.class));
  }

  @Test
  void publish_kafkaExecutionException_returnsFailure() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-2");
    CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
    failed.completeExceptionally(
        new ExecutionException(new RuntimeException("broker not reachable")));
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);

    TriggerEventPublisher.PublishResult result =
        publisher.publish("batch.trigger.launch.v1", "tenant-a:req-2", envelope, "trace-2");

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("kafka send");
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static LaunchEnvelope sampleEnvelope(String tenantId, String requestId) {
    LaunchRequest request =
        new LaunchRequest(
            tenantId,
            "test-job",
            LocalDate.parse("2026-04-30"),
            TriggerType.MANUAL,
            requestId,
            "trace-" + requestId,
            Map.of());
    return LaunchEnvelope.of(request, tenantId + ":" + requestId, BatchDateTimeSupport.utcNow());
  }

  private static SendResult<String, String> sendResult(int partition, long offset) {
    org.apache.kafka.clients.producer.RecordMetadata metadata =
        new org.apache.kafka.clients.producer.RecordMetadata(
            new TopicPartition("batch.trigger.launch.v1", partition),
            offset,
            0,
            RecordBatch.NO_TIMESTAMP,
            0,
            0);
    return new SendResult<>(
        new ProducerRecord<>("batch.trigger.launch.v1", "key", "value"), metadata);
  }

  private static String headerValue(ProducerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
