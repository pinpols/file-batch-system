package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.kafka.BatchEventMessage;
import com.example.batch.common.kafka.BatchMessageType;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.engine.OutboxPublisher;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.EventDeliveryLogMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbox → Kafka 的投递器。
 *
 * <p>输入：一行 {@link OutboxEventEntity}（DB 事实源）<br>
 * 输出：Kafka topic 上的一条消息（字符串 JSON）
 *
 * <p>投递策略：
 *
 * <ul>
 *   <li>若 {@code eventType} 能映射到 dispatch topic（import/export/dispatch），则认为是“任务派发消息”，直接把 {@code
 *       payloadJson} 当作 {@link TaskDispatchMessage} 投递到对应 topic。
 *   <li>若消息指定了 {@code selectedWorkerId}，则投递到“直达 topic”（{@link
 *       BatchTopics#directDispatchTopic(String, String)}）， 用于定向派发/粘性路由。
 *   <li>否则走 fallback topic：把 outbox 包装成 {@link BatchEventMessage}，用于统一审计/调试。
 * </ul>
 *
 * <p>注意：本类只负责“投递动作 + delivery log 记录”，不负责重试调度策略；重试由 outbox forwarder/调度器负责。
 */
@Component
public class KafkaOutboxPublisher implements OutboxPublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final BatchOrchestratorGovernanceProperties governance;
  private final EventDeliveryLogMapper eventDeliveryLogMapper;
  /**
   * delivery-log 写库及回调上的 executor。不能使用 CompletableFuture 默认的 ForkJoinPool.commonPool（共享全局池，
   * 任一耗时回调都会拖累其他业务），也不能在 Kafka producer 的 IO 回调线程上做同步写库（背压风险）。
   * 显式绑定到 Spring Boot 自动装配的 {@code applicationTaskExecutor}，池参数受 {@code spring.task.execution.*} 控制。
   */
  private final Executor deliveryLogExecutor;

  public KafkaOutboxPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      BatchOrchestratorGovernanceProperties governance,
      EventDeliveryLogMapper eventDeliveryLogMapper,
      @Qualifier("applicationTaskExecutor") Executor deliveryLogExecutor) {
    this.kafkaTemplate = kafkaTemplate;
    this.governance = governance;
    this.eventDeliveryLogMapper = eventDeliveryLogMapper;
    this.deliveryLogExecutor = deliveryLogExecutor;
  }

  @Override
  public CompletableFuture<Boolean> publish(OutboxEventEntity event) {
    String topic = governance.mqTopics().resolveDispatchTopic(event.getEventType());
    if (topic != null) {
      // 任务派发：payloadJson 是 TaskDispatchMessage 的 JSON，直接按 eventKey 作为 Kafka key 投递。
      TaskDispatchMessage dispatchMessage =
          JsonUtils.fromJson(event.getPayloadJson(), TaskDispatchMessage.class);
      String targetTopic =
          dispatchMessage != null && dispatchMessage.selectedWorkerId() != null
              ? BatchTopics.directDispatchTopic(topic, dispatchMessage.selectedWorkerId())
              : topic;
      String workerId = dispatchMessage == null ? null : dispatchMessage.selectedWorkerId();
      return kafkaTemplate
          .send(targetTopic, event.getEventKey(), event.getPayloadJson())
          .toCompletableFuture()
          .handleAsync(
              (result, ex) -> {
                if (ex == null) {
                  recordDelivery(
                      event, targetTopic, workerId, OutboxPublishStatus.PUBLISHED.code(), null);
                  return true;
                }
                recordDelivery(
                    event,
                    targetTopic,
                    workerId,
                    OutboxPublishStatus.FAILED.code(),
                    ex.getMessage());
                throw new CompletionException(ex);
              },
              deliveryLogExecutor);
    }

    // fallback：非任务派发类 outbox，统一包装成 BatchEventMessage 投递到默认 topic，便于通用消费者/审计。
    String fallbackTopic = governance.outbox().getDefaultTopic();
    BatchEventMessage message =
        new BatchEventMessage(
            "v1",
            BatchMessageType.OUTBOX_EVENT,
            event.getTenantId(),
            null,
            null,
            null,
            null,
            null,
            event.getTraceId(),
            event.getEventKey(),
            event.getAggregateType(),
            governance.outbox().getProducerName(),
            event.getEventType(),
            fallbackTopic,
            event.getEventKey(),
            event.getCreatedAt(),
            Map.of("payload", JsonUtils.fromJson(event.getPayloadJson(), Object.class)),
            Map.of("aggregateId", event.getAggregateId()));
    return kafkaTemplate
        .send(fallbackTopic, event.getEventKey(), JsonUtils.toJson(message))
        .toCompletableFuture()
        .handleAsync(
            (result, ex) -> {
              if (ex == null) {
                recordDelivery(
                    event, fallbackTopic, null, OutboxPublishStatus.PUBLISHED.code(), null);
                return true;
              }
              recordDelivery(
                  event, fallbackTopic, null, OutboxPublishStatus.FAILED.code(), ex.getMessage());
              throw new CompletionException(ex);
            },
            deliveryLogExecutor);
  }

  // #5-2: 敏感字段关键词，delivery log 中的 payload 需对这些字段脱敏
  private static final List<String> SENSITIVE_KEYS =
      List.of("password", "secret", "token", "credential", "apiKey", "api_key", "accessKey");

  private void recordDelivery(
      OutboxEventEntity event,
      String targetTopic,
      String targetWorkerId,
      String deliveryStatus,
      String errorMessage) {
    EventDeliveryLogEntity log = new EventDeliveryLogEntity();
    log.setTenantId(event.getTenantId());
    log.setOutboxEventId(event.getId());
    log.setEventType(event.getEventType());
    log.setEventKey(event.getEventKey());
    log.setTargetTopic(targetTopic);
    log.setTargetWorkerId(targetWorkerId);
    log.setDeliveryStatus(deliveryStatus);
    log.setDeliveryAttempt(event.getPublishAttempt() == null ? 1 : event.getPublishAttempt() + 1);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("aggregateType", event.getAggregateType());
    summary.put("aggregateId", event.getAggregateId());
    summary.put("payloadPreview", sanitizePayload(event.getPayloadJson()));
    log.setDeliverySummary(JsonUtils.toJson(summary));
    log.setErrorMessage(errorMessage);
    log.setTraceId(event.getTraceId());
    eventDeliveryLogMapper.insert(log);
  }

  @SuppressWarnings("unchecked")
  private static String sanitizePayload(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return payloadJson;
    }
    try {
      Object parsed = JsonUtils.fromJson(payloadJson, Object.class);
      if (parsed instanceof Map<?, ?> map) {
        Map<String, Object> sanitized = new LinkedHashMap<>((Map<String, Object>) map);
        sanitized
            .entrySet()
            .forEach(
                entry -> {
                  if (isSensitiveKey(entry.getKey())) {
                    entry.setValue("***");
                  }
                });
        return JsonUtils.toJson(sanitized);
      }
    } catch (RuntimeException ignored) {
      // payload 不是合法 JSON，原样返回
    }
    return payloadJson;
  }

  private static boolean isSensitiveKey(String key) {
    if (key == null) {
      return false;
    }
    String lower = key.toLowerCase();
    return SENSITIVE_KEYS.stream().anyMatch(s -> lower.contains(s.toLowerCase()));
  }
}
