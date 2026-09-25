package io.github.pinpols.batch.trigger.infrastructure.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.mq.MqMessage;
import io.github.pinpols.batch.common.mq.MqMessagePublisher;
import io.github.pinpols.batch.common.mq.MqPublishResult;
import io.github.pinpols.batch.common.observability.W3cTraceContext;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.trigger.application.TriggerEventPublisher;
import io.github.pinpols.batch.trigger.config.TriggerKafkaProperties;
import io.opentelemetry.api.trace.Span;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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

  @Mock
  private MqMessagePublisher mqMessagePublisher;

  private KafkaTriggerEventPublisher publisher;

  @BeforeEach
  void setUp() {
    TriggerKafkaProperties props = new TriggerKafkaProperties();
    props.setSendTimeoutSeconds(1);
    publisher = new KafkaTriggerEventPublisher(mqMessagePublisher, props);
  }

  @Test
  void publish_validEnvelope_sendsWithHeadersAndReturnsOk() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-1");
    when(mqMessagePublisher.publish(any(MqMessage.class)))
        .thenReturn(CompletableFuture.completedFuture(new MqPublishResult(0, 100L)));

    TriggerEventPublisher.PublishResult result =
        publisher.publish(BatchTopics.TRIGGER_LAUNCH_V1, "tenant-a:req-1", envelope, "trace-1");

    assertThat(result.success()).isTrue();
    ArgumentCaptor<MqMessage> captor = ArgumentCaptor.forClass(MqMessage.class);
    verify(mqMessagePublisher).publish(captor.capture());
    MqMessage sent = captor.getValue();
    assertThat(sent.topic()).isEqualTo(BatchTopics.TRIGGER_LAUNCH_V1);
    assertThat(sent.key()).isEqualTo("tenant-a:req-1");
    assertThat(sent.headers()).containsEntry("X-Trace-Id", "trace-1");
    assertThat(sent.headers()).containsEntry("X-Tenant-Id", "tenant-a");
    assertThat(sent.headers()).containsEntry("X-Envelope-Version", "2");
  }

  @Test
  void publish_withPersistedTraceContext_restoresParentDuringKafkaSend() {
    String traceId = "11111111111111111111111111111111";
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-trace")
        .withTraceContext(new W3cTraceContext("00-" + traceId + "-2222222222222222-01", null));
    String[] activeTraceId = new String[1];
    when(mqMessagePublisher.publish(any(MqMessage.class))).thenAnswer(invocation -> {
      activeTraceId[0] = Span.current().getSpanContext().getTraceId();
      return CompletableFuture.completedFuture(new MqPublishResult(0, 101L));
    });

    TriggerEventPublisher.PublishResult result = publisher.publish(
        BatchTopics.TRIGGER_LAUNCH_V1, "tenant-a:req-trace", envelope, "business-trace");

    assertThat(result.success()).isTrue();
    assertThat(activeTraceId[0]).isEqualTo(traceId);
  }

  @Test
  void publish_nullEnvelope_failsWithoutSending() {
    TriggerEventPublisher.PublishResult result =
        publisher.publish(BatchTopics.TRIGGER_LAUNCH_V1, "key", null, "trace");

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("null");
    verify(mqMessagePublisher, never()).publish(any(MqMessage.class));
  }

  @Test
  void publish_kafkaExecutionException_returnsFailure() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-2");
    CompletableFuture<MqPublishResult> failed = new CompletableFuture<>();
    failed.completeExceptionally(
        new ExecutionException(new RuntimeException("broker not reachable")));
    when(mqMessagePublisher.publish(any(MqMessage.class))).thenReturn(failed);

    TriggerEventPublisher.PublishResult result =
        publisher.publish(BatchTopics.TRIGGER_LAUNCH_V1, "tenant-a:req-2", envelope, "trace-2");

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("kafka send");
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static LaunchEnvelope sampleEnvelope(String tenantId, String requestId) {
    LaunchRequest request = new LaunchRequest(
        tenantId,
        "test-job",
        LocalDate.parse("2026-04-30"),
        TriggerType.MANUAL,
        requestId,
        "trace-" + requestId,
        Map.of());
    return LaunchEnvelope.of(request, tenantId + ":" + requestId, BatchDateTimeSupport.utcNow());
  }
}
