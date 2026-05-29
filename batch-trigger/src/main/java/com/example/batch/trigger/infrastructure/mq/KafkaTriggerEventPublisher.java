package com.example.batch.trigger.infrastructure.mq;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.trigger.application.TriggerEventPublisher;
import com.example.batch.trigger.config.TriggerKafkaProperties;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * ADR-010 Stage 4: {@link TriggerEventPublisher} 的 Kafka 实现。
 *
 * <p>同步阻塞发送(返回时表示 broker 已 ack 或失败已确定),被 {@link
 * com.example.batch.trigger.application.TriggerOutboxRelay} 在 ShedLock 内逐条调用,无需异步。
 *
 * <p>ADR-010 固化路径，无条件实例化（2026-05-02 同步 HTTP 路径已删除）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaTriggerEventPublisher implements TriggerEventPublisher {

  private static final String HEADER_TRACE_ID = "X-Trace-Id";
  private static final String HEADER_TENANT_ID = "X-Tenant-Id";
  private static final String HEADER_ENVELOPE_VERSION = "X-Envelope-Version";

  private final KafkaTemplate<String, String> triggerKafkaTemplate;
  private final TriggerKafkaProperties kafkaProperties;

  @Override
  public PublishResult publish(
      String topic, String messageKey, LaunchEnvelope envelope, String traceId) {
    if (envelope == null || envelope.launchRequest() == null) {
      return PublishResult.fail("envelope or launchRequest is null");
    }
    String payload;
    try {
      payload = JsonUtils.toJson(envelope);
    } catch (RuntimeException ex) {
      log.error(
          "KafkaTriggerEventPublisher 序列化 envelope 失败: tenantId={} requestId={}",
          envelope.launchRequest().tenantId(),
          envelope.launchRequest().requestId(),
          ex);
      return PublishResult.fail("serialize envelope: " + ex.getMessage());
    }
    ProducerRecord<String, String> record = new ProducerRecord<>(topic, messageKey, payload);
    if (traceId != null && !traceId.isBlank()) {
      record
          .headers()
          .add(new RecordHeader(HEADER_TRACE_ID, traceId.getBytes(StandardCharsets.UTF_8)));
    }
    if (envelope.launchRequest().tenantId() != null) {
      record
          .headers()
          .add(
              new RecordHeader(
                  HEADER_TENANT_ID,
                  envelope.launchRequest().tenantId().getBytes(StandardCharsets.UTF_8)));
    }
    record
        .headers()
        .add(
            new RecordHeader(
                HEADER_ENVELOPE_VERSION,
                String.valueOf(envelope.envelopeVersion()).getBytes(StandardCharsets.UTF_8)));
    try {
      SendResult<String, String> result =
          triggerKafkaTemplate
              .send(record)
              .get(kafkaProperties.getSendTimeoutSeconds(), TimeUnit.SECONDS);
      log.debug(
          "KafkaTriggerEventPublisher 发送成功: topic={} key={} partition={} offset={}",
          topic,
          messageKey,
          result.getRecordMetadata().partition(),
          result.getRecordMetadata().offset());
      return PublishResult.ok();
    } catch (TimeoutException ex) {
      SwallowedExceptionLogger.info(KafkaTriggerEventPublisher.class, "catch:TimeoutException", ex);

      return PublishResult.fail(
          "kafka send timeout " + kafkaProperties.getSendTimeoutSeconds() + "s");
    } catch (ExecutionException ex) {
      Throwable cause = ex.getCause() == null ? ex : ex.getCause();
      // R2-P2-6：之前完全无日志 → 不可恢复错误（AuthorizationException / RecordTooLarge /
      // InvalidTopic）会耗光全部 retry 直至 GIVE_UP，运维无实时信号。改为 ERROR + stack。
      log.error(
          "kafka publish failed (will retry until GIVE_UP): topic={} messageKey={} cause={}",
          topic,
          messageKey,
          cause.getMessage(),
          cause);
      return PublishResult.fail("kafka send: " + cause.getMessage());
    } catch (InterruptedException ex) {
      SwallowedExceptionLogger.info(
          KafkaTriggerEventPublisher.class, "catch:InterruptedException", ex);

      Thread.currentThread().interrupt();
      return PublishResult.fail("kafka send interrupted");
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
      return PublishResult.fail("kafka send sync: " + ex.getMessage());
    }
  }
}
