package io.github.pinpols.batch.trigger.infrastructure.mq;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.trigger.application.TriggerEventPublisher;
import io.github.pinpols.batch.trigger.config.TriggerKafkaProperties;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * ADR-010 Stage 4: {@link TriggerEventPublisher} 的 Kafka 实现。
 *
 * <p>relay 以批为单位异步发起发送，再等该批 Kafka ACK；producer 因此可以复用其内置的请求合并与 connection in-flight
 * 能力，而不会把每条消息的 broker RTT 串行化。
 *
 * <p>ADR-010 固化路径，无条件实例化（2026-05-02 同步 HTTP 路径已删除）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaTriggerEventPublisher implements TriggerEventPublisher {

  private static final String HEADER_TRACE_ID = CommonConstants.DEFAULT_TRACE_ID_HEADER;
  private static final String HEADER_TENANT_ID = CommonConstants.DEFAULT_TENANT_ID_HEADER;
  private static final String HEADER_ENVELOPE_VERSION = "X-Envelope-Version";

  private final KafkaTemplate<String, String> triggerKafkaTemplate;
  private final TriggerKafkaProperties kafkaProperties;

  @Override
  public CompletableFuture<PublishResult> publishAsync(
      String topic, String messageKey, LaunchEnvelope envelope, String traceId) {
    if (EmptyChecks.isNull(envelope) || EmptyChecks.isNull(envelope.launchRequest())) {
      return CompletableFuture.completedFuture(
          PublishResult.fail("envelope or launchRequest is null"));
    }
    String payload;
    try {
      payload = JsonUtils.toJson(envelope);
    } catch (RuntimeException ex) {
      log.error(
          "KafkaTriggerEventPublisher failed to serialize envelope: tenantId={} requestId={}",
          envelope.launchRequest().tenantId(),
          envelope.launchRequest().requestId(),
          ex);
      return CompletableFuture.completedFuture(
          PublishResult.fail("serialize envelope: " + ex.getMessage()));
    }
    ProducerRecord<String, String> producerRecord =
        new ProducerRecord<>(topic, messageKey, payload);
    if (EmptyChecks.isNotBlank(traceId)) {
      producerRecord
          .headers()
          .add(new RecordHeader(HEADER_TRACE_ID, traceId.getBytes(StandardCharsets.UTF_8)));
    }
    if (EmptyChecks.isNotNull(envelope.launchRequest().tenantId())) {
      producerRecord
          .headers()
          .add(new RecordHeader(
              HEADER_TENANT_ID,
              envelope.launchRequest().tenantId().getBytes(StandardCharsets.UTF_8)));
    }
    producerRecord
        .headers()
        .add(new RecordHeader(
            HEADER_ENVELOPE_VERSION,
            String.valueOf(envelope.envelopeVersion()).getBytes(StandardCharsets.UTF_8)));
    try {
      return triggerKafkaTemplate
          .send(producerRecord)
          .orTimeout(kafkaProperties.getSendTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)
          .handle((result, throwable) -> toPublishResult(topic, messageKey, result, throwable));
    } catch (RuntimeException ex) {
      // KafkaProducer.send() 在 ensureValidRecordSize / SerializationException 等校验失败时
      // 同步抛 RuntimeException,绕过上面 ExecutionException catch。补 broad catch 避免 outbox
      // 卡 PUBLISHING attempt=0 永远不进 retry/GIVE_UP 路径(A2 agent 发现的真因)。
      log.error(
          "kafka publish failed synchronously (will retry until GIVE_UP):"
              + " topic={} messageKey={} cause={}",
          topic,
          messageKey,
          ex.getMessage(),
          ex);
      return CompletableFuture.completedFuture(
          PublishResult.fail("kafka send sync: " + ex.getMessage()));
    }
  }

  @Override
  public PublishResult publish(
      String topic, String messageKey, LaunchEnvelope envelope, String traceId) {
    return publishAsync(topic, messageKey, envelope, traceId).join();
  }

  private PublishResult toPublishResult(
      String topic,
      String messageKey,
      org.springframework.kafka.support.SendResult<String, String> result,
      Throwable throwable) {
    if (EmptyChecks.isNull(throwable)) {
      log.debug(
          "KafkaTriggerEventPublisher published successfully: topic={} key={} partition={} offset={}",
          topic,
          messageKey,
          result.getRecordMetadata().partition(),
          result.getRecordMetadata().offset());
      return PublishResult.ok();
    }
    Throwable cause = unwrapCompletionException(throwable);
    if (cause instanceof java.util.concurrent.TimeoutException) {
      return PublishResult.fail(
          "kafka send timeout " + kafkaProperties.getSendTimeoutSeconds() + "s");
    }
    log.error(
        "kafka publish failed (will retry until GIVE_UP): topic={} messageKey={} cause={}",
        topic,
        messageKey,
        cause.getMessage(),
        cause);
    return PublishResult.fail("kafka send: " + cause.getMessage());
  }

  private static Throwable unwrapCompletionException(Throwable throwable) {
    if (throwable instanceof CompletionException && EmptyChecks.isNotNull(throwable.getCause())) {
      return throwable.getCause();
    }
    return throwable;
  }
}
