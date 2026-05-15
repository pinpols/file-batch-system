package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.i18n.BizExceptionUtils;
import com.example.batch.common.i18n.BizMessageResolver;
import com.example.batch.common.i18n.LocalizedError;
import com.example.batch.common.kafka.BatchEventMessage;
import com.example.batch.common.kafka.BatchMessageType;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.engine.OutboxPublisher;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.EventDeliveryLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private final BatchTopicResolver topicResolver;
  private final BizMessageResolver bizMessageResolver;
  private final ObjectMapper objectMapper;

  /**
   * delivery-log 写库及回调上的 executor。不能使用 CompletableFuture 默认的 ForkJoinPool.commonPool（共享全局池，
   * 任一耗时回调都会拖累其他业务），也不能在 Kafka producer 的 IO 回调线程上做同步写库（背压风险）。 显式绑定到 Spring Boot 自动装配的 {@code
   * applicationTaskExecutor}，池参数受 {@code spring.task.execution.*} 控制。
   */
  private final Executor deliveryLogExecutor;

  public KafkaOutboxPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      BatchOrchestratorGovernanceProperties governance,
      EventDeliveryLogMapper eventDeliveryLogMapper,
      BatchTopicResolver topicResolver,
      BizMessageResolver bizMessageResolver,
      ObjectMapper objectMapper,
      @Qualifier("applicationTaskExecutor") Executor deliveryLogExecutor) {
    this.kafkaTemplate = kafkaTemplate;
    this.governance = governance;
    this.eventDeliveryLogMapper = eventDeliveryLogMapper;
    this.topicResolver = topicResolver;
    this.bizMessageResolver = bizMessageResolver;
    this.objectMapper = objectMapper;
    this.deliveryLogExecutor = deliveryLogExecutor;
  }

  @Override
  public CompletableFuture<Boolean> publish(OutboxEventEntity event) {
    // P2-5: dispatch 消息按 routing.mode 走 (tenant|priority|single) 分流；非派发类 fallback 不变
    TaskDispatchMessage dispatchMessage = null;
    if (governance.mqTopics().resolveDispatchTopic(event.getEventType()) != null) {
      dispatchMessage = JsonUtils.fromJson(event.getPayloadJson(), TaskDispatchMessage.class);
    }
    String topic = topicResolver.resolve(event.getEventType(), dispatchMessage);
    if (topic != null) {
      // 当指定了 selectedWorkerId 时，走 node-direct 拓扑（base.node.{workerId}），跳过
      // TENANT/PRIORITY 后缀。worker 端 topicPattern 仅匹配 base / base.<single-segment> /
      // base.node.<workerCode> 三种形式，**不**匹配组合形式 base.<tenant>.node.<workerCode>，
      // 否则会出现 producer 写到 base.t1.node.X 但 worker 订阅 base.node.X 的不匹配，
      // 任务卡在 CREATED 永不下发（被 e2e ImportFailureE2eIT/OutboxForwarderE2eIT 等暴露）。
      String targetTopic;
      if (dispatchMessage != null && dispatchMessage.selectedWorkerId() != null) {
        String baseTopic = governance.mqTopics().resolveDispatchTopic(event.getEventType());
        targetTopic =
            BatchTopics.directDispatchTopic(baseTopic, dispatchMessage.selectedWorkerId());
      } else {
        targetTopic = topic;
      }
      String workerId = dispatchMessage == null ? null : dispatchMessage.selectedWorkerId();
      return kafkaTemplate
          .send(targetTopic, event.getEventKey(), event.getPayloadJson())
          .toCompletableFuture()
          .handleAsync(
              (result, ex) -> {
                if (ex == null) {
                  recordDelivery(
                      event,
                      targetTopic,
                      workerId,
                      OutboxPublishStatus.PUBLISHED.code(),
                      LocalizedError.EMPTY);
                  return true;
                }
                recordDelivery(
                    event,
                    targetTopic,
                    workerId,
                    OutboxPublishStatus.FAILED.code(),
                    BizExceptionUtils.toLocalizedError(ex, bizMessageResolver, objectMapper));
                throw new CompletionException(ex);
              },
              deliveryLogExecutor);
    }

    // R6 P0-4：ADR-030 §F verifier.failure.v1 走专用 topic，运维告警 / SLO 直接订阅，
    // 不再混入通用 outbox fallback 桶（之前会被 ops 默认 ACL 屏蔽，告警系统拿不到）。
    String dedicatedTopic = resolveDedicatedTopic(event.getEventType());
    if (dedicatedTopic != null) {
      return kafkaTemplate
          .send(dedicatedTopic, event.getEventKey(), event.getPayloadJson())
          .toCompletableFuture()
          .handleAsync(
              (result, ex) -> {
                if (ex == null) {
                  recordDelivery(
                      event,
                      dedicatedTopic,
                      null,
                      OutboxPublishStatus.PUBLISHED.code(),
                      LocalizedError.EMPTY);
                  return true;
                }
                recordDelivery(
                    event,
                    dedicatedTopic,
                    null,
                    OutboxPublishStatus.FAILED.code(),
                    BizExceptionUtils.toLocalizedError(ex, bizMessageResolver, objectMapper));
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
                    event,
                    fallbackTopic,
                    null,
                    OutboxPublishStatus.PUBLISHED.code(),
                    LocalizedError.EMPTY);
                return true;
              }
              recordDelivery(
                  event,
                  fallbackTopic,
                  null,
                  OutboxPublishStatus.FAILED.code(),
                  BizExceptionUtils.toLocalizedError(ex, bizMessageResolver, objectMapper));
              throw new CompletionException(ex);
            },
            deliveryLogExecutor);
  }

  /**
   * R6 P0-4：event_type → 专用 topic 映射。命中即走专用 topic，未命中走 fallback。
   *
   * <p>当前覆盖：{@code verifier.failure.v1}（ADR-030 §F 失败事件，运维告警面板独立订阅）。
   */
  private static final Set<String> VERIFIER_FAILURE_EVENT_TYPES = Set.of("verifier.failure.v1");

  private static String resolveDedicatedTopic(String eventType) {
    if (eventType != null && VERIFIER_FAILURE_EVENT_TYPES.contains(eventType)) {
      return BatchTopics.VERIFIER_FAILURE_V1;
    }
    return null;
  }

  // #5-2: 敏感字段关键词，delivery log 中的 payload 需对这些字段脱敏
  private static final List<String> SENSITIVE_KEYS =
      List.of("password", "secret", "token", "credential", "apiKey", "api_key", "accessKey");

  /** 成功路径调用:errorMessage 传 null, 没有 i18n key/args。 */
  private void recordDelivery(
      OutboxEventEntity event,
      String targetTopic,
      String targetWorkerId,
      String deliveryStatus,
      String errorMessage) {
    recordDelivery(
        event,
        targetTopic,
        targetWorkerId,
        deliveryStatus,
        BizExceptionUtils.ofLiteral(errorMessage));
  }

  /** 失败路径调用:从 Throwable 提取 i18n key/args + 渲染 message,持久化三元组。 */
  private void recordDelivery(
      OutboxEventEntity event,
      String targetTopic,
      String targetWorkerId,
      String deliveryStatus,
      LocalizedError error) {
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
    if (error != null) {
      log.setErrorMessage(error.renderedMessage());
      log.setErrorKey(error.key());
      log.setErrorArgs(error.argsJson());
    }
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
      SwallowedExceptionLogger.warn(KafkaOutboxPublisher.class, "catch:RuntimeException", ignored);

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
