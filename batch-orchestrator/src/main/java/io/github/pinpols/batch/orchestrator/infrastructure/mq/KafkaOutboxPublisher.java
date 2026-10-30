package io.github.pinpols.batch.orchestrator.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.i18n.BizExceptionUtils;
import io.github.pinpols.batch.common.i18n.BizMessageResolver;
import io.github.pinpols.batch.common.i18n.LocalizedError;
import io.github.pinpols.batch.common.kafka.BatchEventMessage;
import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchMessageType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.mq.MqMessage;
import io.github.pinpols.batch.common.mq.MqMessagePublisher;
import io.github.pinpols.batch.common.mq.MqPublishResult;
import io.github.pinpols.batch.common.observability.OtelTracePropagation;
import io.github.pinpols.batch.common.observability.W3cTraceContext;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.orchestrator.application.engine.OutboxPublisher;
import io.github.pinpols.batch.orchestrator.application.outbox.OutboxChildRecordService;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.OutboxEventEntity;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.apache.kafka.common.utils.Utils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

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

  private final MqMessagePublisher mqMessagePublisher;
  private final BatchOrchestratorGovernanceProperties governance;
  private final OutboxChildRecordService outboxChildRecordService;
  private final BatchTopicResolver topicResolver;
  private final BatchKafkaTopicNamespace topicNamespace;
  private final BizMessageResolver bizMessageResolver;
  private final ObjectMapper objectMapper;

  /**
   * delivery-log 写库及回调上的 executor。不能使用 CompletableFuture 默认的 ForkJoinPool.commonPool（共享全局池，
   * 任一耗时回调都会拖累其他业务），也不能在 Kafka producer 的 IO 回调线程上做同步写库（背压风险）。 显式绑定到 Spring Boot 自动装配的 {@code
   * applicationTaskExecutor}，池参数受 {@code spring.task.execution.*} 控制。
   */
  private final Executor deliveryLogExecutor;

  public KafkaOutboxPublisher(
      @Qualifier("orchestratorMqMessagePublisher") MqMessagePublisher mqMessagePublisher,
      BatchOrchestratorGovernanceProperties governance,
      OutboxChildRecordService outboxChildRecordService,
      BatchTopicResolver topicResolver,
      BatchKafkaTopicNamespace topicNamespace,
      BizMessageResolver bizMessageResolver,
      ObjectMapper objectMapper,
      @Qualifier("applicationTaskExecutor") Executor deliveryLogExecutor) {
    this.mqMessagePublisher = mqMessagePublisher;
    this.governance = governance;
    this.outboxChildRecordService = outboxChildRecordService;
    this.topicResolver = topicResolver;
    this.topicNamespace = topicNamespace;
    this.bizMessageResolver = bizMessageResolver;
    this.objectMapper = objectMapper;
    this.deliveryLogExecutor = deliveryLogExecutor;
  }

  @Override
  // S2259 无法识别 EmptyChecks.isNull(dispatchMessage) 对可空消息载荷的保护。
  @SuppressWarnings("java:S2259")
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
        String baseTopic = topicResolver.resolveBaseTopic(event.getEventType());
        targetTopic =
            BatchTopics.directDispatchTopic(baseTopic, dispatchMessage.selectedWorkerId());
      } else {
        targetTopic = topic;
      }
      String workerId = dispatchMessage == null ? null : dispatchMessage.selectedWorkerId();
      String kafkaKey = dispatchKafkaKey(event, dispatchMessage);
      String payloadJson = event.getPayloadJson();
      W3cTraceContext traceContext =
          EmptyChecks.isNull(dispatchMessage) ? null : dispatchMessage.traceContext();
      CompletableFuture<MqPublishResult> publishFuture =
          sendWithTraceContext(targetTopic, kafkaKey, payloadJson, traceContext);
      return publishFuture.handleAsync(
          (result, ex) -> {
            if (EmptyChecks.isNull(ex)) {
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
    String resolvedDedicatedTopic = resolveDedicatedTopic(event.getEventType());
    String dedicatedTopic = EmptyChecks.isNull(resolvedDedicatedTopic)
        ? null
        : topicNamespace.topic(resolvedDedicatedTopic);
    if (dedicatedTopic != null) {
      MqMessage dedicatedMessage =
          MqMessage.of(dedicatedTopic, event.getEventKey(), event.getPayloadJson());
      CompletableFuture<MqPublishResult> publishFuture =
          mqMessagePublisher.publish(dedicatedMessage);
      return publishFuture.handleAsync(
          (result, ex) -> {
            if (EmptyChecks.isNull(ex)) {
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
    String fallbackTopic = topicNamespace.topic(governance.outbox().getDefaultTopic());
    Object payload = JsonUtils.fromJson(event.getPayloadJson(), Object.class);
    Map<String, Object> payloadFields = Map.of("payload", payload);
    BatchEventMessage message = BatchEventMessage.builder()
        .schemaVersion("v1")
        .messageType(BatchMessageType.OUTBOX_EVENT)
        .tenantId(event.getTenantId())
        .traceId(event.getTraceId())
        .idempotencyKey(event.getEventKey())
        .businessKey(event.getAggregateType())
        .producer(governance.outbox().getProducerName())
        .eventName(event.getEventType())
        .topic(fallbackTopic)
        .key(event.getEventKey())
        .eventTime(event.getCreatedAt())
        .payload(payloadFields)
        .ext(Map.of("aggregateId", event.getAggregateId()))
        .build();
    String messageEventKey = event.getEventKey();
    String messageJson = JsonUtils.toJson(message);
    MqMessage mqMessage = MqMessage.of(fallbackTopic, messageEventKey, messageJson);
    CompletableFuture<MqPublishResult> publishFuture = mqMessagePublisher.publish(mqMessage);
    return publishFuture.handleAsync(
        (result, ex) -> {
          if (EmptyChecks.isNull(ex)) {
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

  private CompletableFuture<MqPublishResult> sendWithTraceContext(
      String topic, String key, String payload, W3cTraceContext traceContext) {
    try (Scope ignored = OtelTracePropagation.restore(traceContext)) {
      return mqMessagePublisher.publish(MqMessage.of(topic, key, payload));
    }
  }

  /**
   * R6 P0-4：event_type → 专用 topic 映射。命中即走专用 topic，未命中走 fallback。
   *
   * <p>当前覆盖：{@code verifier.failure.v1}（运维告警）与 Job/Workflow 终态事件（可靠血缘/业务通知投递）。
   */
  private static final Set<String> VERIFIER_FAILURE_EVENT_TYPES = Set.of("verifier.failure.v1");

  private static String resolveDedicatedTopic(String eventType) {
    if (eventType != null && VERIFIER_FAILURE_EVENT_TYPES.contains(eventType)) {
      return BatchTopics.VERIFIER_FAILURE_V1;
    }
    if ("WORKFLOW_LAUNCH".equals(eventType)) {
      return BatchTopics.TRIGGER_LAUNCH_V1;
    }
    if ("JOB_TERMINAL".equals(eventType)) {
      return BatchTopics.JOB_TERMINAL_V1;
    }
    if ("WORKFLOW_TERMINAL".equals(eventType)) {
      return BatchTopics.WORKFLOW_TERMINAL_V1;
    }
    return null;
  }

  static String dispatchKafkaKey(OutboxEventEntity event, TaskDispatchMessage message) {
    if (message == null) {
      return event.getEventKey();
    }
    String plannedPartitionKey = plannedPartitionKafkaKey(message);
    if (plannedPartitionKey != null) {
      return plannedPartitionKey;
    }
    if (message.jobPartitionId() != null) {
      return message.tenantId()
          + ":"
          + message.jobCode()
          + ":"
          + message.instanceNo()
          + ":"
          + message.jobPartitionId();
    }
    if (message.taskId() != null) {
      return message.tenantId()
          + ":"
          + message.jobCode()
          + ":"
          + message.instanceNo()
          + ":task:"
          + message.taskId();
    }
    return event.getEventKey();
  }

  private static String plannedPartitionKafkaKey(TaskDispatchMessage message) {
    Integer partitionNo = message.partitionNo();
    Integer partitionCount = message.partitionCount();
    boolean partitionMetadataIsInvalid = EmptyChecks.isNull(partitionNo)
        || EmptyChecks.isNull(partitionCount)
        || partitionNo <= 0
        || partitionCount <= 1;
    if (partitionMetadataIsInvalid) {
      return null;
    }
    int targetPartition = Math.floorMod(partitionNo - 1, partitionCount);
    String base = message.tenantId()
        + ":"
        + message.jobCode()
        + ":"
        + message.instanceNo()
        + ":part:"
        + partitionNo
        + "of"
        + partitionCount;
    for (int salt = 0; salt < 1024; salt++) {
      String candidate = base + ":k" + salt;
      if (partitionFor(candidate, partitionCount) == targetPartition) {
        return candidate;
      }
    }
    return base;
  }

  static int partitionFor(String key, int partitionCount) {
    if (partitionCount <= 0) {
      return 0;
    }
    byte[] bytes = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    int positiveHash = Utils.toPositive(Utils.murmur2(bytes));
    return positiveHash % partitionCount;
  }

  // #5-2: 敏感字段关键词，delivery log 中的 payload 需对这些字段脱敏
  private static final List<String> SENSITIVE_KEYS =
      List.of("password", "secret", "token", "credential", "apiKey", "api_key", "accessKey");

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
    log.setOutboxEventCreatedAt(event.getCreatedAt());
    log.setEventType(event.getEventType());
    log.setEventKey(event.getEventKey());
    log.setTargetTopic(targetTopic);
    log.setTargetWorkerId(targetWorkerId);
    log.setDeliveryStatus(deliveryStatus);
    log.setDeliveryAttempt(event.getPublishAttempt() == null ? 1 : event.getPublishAttempt() + 1);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("aggregateType", event.getAggregateType());
    summary.put("aggregateId", event.getAggregateId());
    String payloadJson = event.getPayloadJson();
    String payloadPreview = sanitizePayload(payloadJson);
    summary.put("payloadPreview", payloadPreview);
    log.setDeliverySummary(JsonUtils.toJson(summary));
    if (error != null) {
      log.setErrorMessage(safeDeliveryErrorText(error.renderedMessage()));
      log.setErrorKey(error.key());
      log.setErrorArgs(safeDeliveryErrorArgs(error.argsJson()));
    }
    log.setTraceId(event.getTraceId());
    outboxChildRecordService.recordDelivery(log);
  }

  private static String safeDeliveryErrorText(String value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String normalized = ConsoleTextSanitizer.normalize(HtmlUtils.htmlUnescape(value));
    if (!SwallowedExceptionLogger.containsCredentialSyntax(normalized)) {
      return value;
    }
    int maxLength = Math.max(4, normalized.length());
    return SwallowedExceptionLogger.safeMessage(normalized, maxLength);
  }

  private static String safeDeliveryErrorArgs(String argsJson) {
    if (EmptyChecks.isNull(argsJson) || argsJson.isBlank()) { // empty-check: allow - 保留 JDK 字符串空白判定
      return argsJson;
    }
    try {
      Object parsed = JsonUtils.fromJson(argsJson, Object.class);
      return JsonUtils.toJson(maskDeliveryErrorValue(parsed));
    } catch (RuntimeException failure) {
      // 参数无法安全解析时不持久化原文，保留已净化的错误消息。
      return null;
    }
  }

  private static Object maskDeliveryErrorValue(Object value) {
    if (value instanceof String text) {
      return safeDeliveryErrorText(text);
    }
    if (value instanceof List<?> items) {
      return items.stream().map(KafkaOutboxPublisher::maskDeliveryErrorValue).toList();
    }
    if (value instanceof Map<?, ?> entries) {
      Map<String, Object> masked = new LinkedHashMap<>();
      entries.forEach((key, item) -> {
        String name = String.valueOf(key);
        String normalizedName = ConsoleTextSanitizer.normalize(HtmlUtils.htmlUnescape(name));
        Object safeItem = SecretMasking.isSensitive(normalizedName) && EmptyChecks.isNotNull(item)
            ? SecretMasking.MASKED_VALUE
            : maskDeliveryErrorValue(item);
        masked.put(name, safeItem);
      });
      return masked;
    }
    return value;
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
        sanitized.entrySet().forEach(entry -> {
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
    String lower = key.toLowerCase(Locale.ROOT);
    return SENSITIVE_KEYS.stream().anyMatch(s -> lower.contains(s.toLowerCase(Locale.ROOT)));
  }
}
