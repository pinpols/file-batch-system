package com.example.batch.trigger.infrastructure.mq;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.trigger.application.TriggerEventPublisher;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * ADR-010 Stage 4: {@link TriggerEventPublisher} 的 Kafka 实现。
 *
 * <p>同步阻塞发送(返回时表示 broker 已 ack 或失败已确定),被 {@link
 * com.example.batch.trigger.application.TriggerOutboxRelay} 在 ShedLock 内逐条调用,无需异步。
 *
 * <p>仅当 {@code batch.trigger.async-launch.enabled=true} 时实例化(与 producer config + relay 同条件)。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    prefix = "batch.trigger.async-launch",
    name = "enabled",
    havingValue = "true")
public class KafkaTriggerEventPublisher implements TriggerEventPublisher {

  private static final String HEADER_TRACE_ID = "X-Trace-Id";
  private static final String HEADER_TENANT_ID = "X-Tenant-Id";
  private static final String HEADER_ENVELOPE_VERSION = "X-Envelope-Version";

  private final KafkaTemplate<String, String> triggerKafkaTemplate;

  @Value("${batch.trigger.kafka.send-timeout-seconds:10}")
  private int sendTimeoutSeconds;

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
          triggerKafkaTemplate.send(record).get(sendTimeoutSeconds, TimeUnit.SECONDS);
      log.debug(
          "KafkaTriggerEventPublisher 发送成功: topic={} key={} partition={} offset={}",
          topic,
          messageKey,
          result.getRecordMetadata().partition(),
          result.getRecordMetadata().offset());
      return PublishResult.ok();
    } catch (TimeoutException ex) {
      return PublishResult.fail("kafka send timeout " + sendTimeoutSeconds + "s");
    } catch (ExecutionException ex) {
      Throwable cause = ex.getCause() == null ? ex : ex.getCause();
      return PublishResult.fail("kafka send: " + cause.getMessage());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return PublishResult.fail("kafka send interrupted");
    }
  }
}
