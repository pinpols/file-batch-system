package io.github.pinpols.batch.orchestrator.application.trigger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import io.github.pinpols.batch.orchestrator.config.TriggerConsumerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-010 Stage 4: 消费 batch.trigger.launch.v1 topic,把 envelope 反序列化后调用现有 {@link
 * LaunchApplicationService#launchFromTrustedQueue(LaunchRequest)} 内部 API。
 *
 * <p>幂等保证:同 requestId 多次消费 → orchestrator 端 {@code uk_job_instance_tenant_dedup} 回退,不会真正双跑。重复消费时
 * launch 抛 CONFLICT(409), 我们视为成功 ack(消息已被处理过,不需要重投)。
 *
 * <p>失败处理:
 *
 * <ul>
 *   <li>空消息、反序列化失败和不可重试业务拒绝 → 记录指标后明确 ack，避免毒消息阻塞 partition
 *   <li>幂等冲突(409) → 视为已处理并 ack
 *   <li>容量反压(429) → 显式 nack 当前 offset 并暂停 partition，不进入有限次数 recoverer
 *   <li>其他 unchecked → 交给容器有限重试；耗尽后按当前 LOG_ONLY 策略记录错误并提交 offset
 * </ul>
 *
 * <p>ADR-010 固化路径，无条件实例化（2026-05-02 同步 HTTP 路径已删除）。
 */
@Component
@Slf4j
public class TriggerLaunchConsumer {

  private static final String METRIC_CONSUMED = "batch.trigger.launch.consumed.total";
  private static final String METRIC_DEDUPED = "batch.trigger.launch.deduped.total";
  private static final String METRIC_FAILED = "batch.trigger.launch.failed.total";
  private static final String METRIC_CONSUME_DURATION = "batch.trigger.launch.consume.duration";
  private static final String METRIC_KAFKA_QUEUE_AGE = "batch.trigger.launch.kafka.queue.age";

  // R3-P0-11：Kafka 消息可注入任意 tenantId 字符串 → Prometheus TSDB cardinality 爆失败。
  // 用 ConcurrentHashMap.newKeySet 缓存已观测 tenantId，超过阈值后新 tenant 统一归一化为 "other"。
  // 256 是 Prom 单 metric 系列上限的实用阈值（足够区分常见租户 + 容忍误差）。
  private static final int MAX_TENANT_TAG_CARDINALITY = 256;
  private static final Set<String> OBSERVED_TENANTS = ConcurrentHashMap.newKeySet();
  private static final long MAX_COUNTER_CACHE_SIZE = 2_048L;

  private String normalizeTenantTag(String tenantId) {
    if (EmptyChecks.isBlank(tenantId)) {
      return "unknown";
    }
    if (OBSERVED_TENANTS.size() < MAX_TENANT_TAG_CARDINALITY) {
      OBSERVED_TENANTS.add(tenantId);
      return tenantId;
    }
    return OBSERVED_TENANTS.contains(tenantId) ? tenantId : "other";
  }

  private final LaunchApplicationService launchApplicationService;
  private final MeterRegistry meterRegistry;
  private final Timer consumeTimer;
  private final Timer kafkaQueueAgeTimer;
  private final Cache<MetricKey, Counter> counterCache;
  private final Duration rateLimitBackoff;

  public TriggerLaunchConsumer(
      LaunchApplicationService launchApplicationService,
      MeterRegistry meterRegistry,
      TriggerConsumerProperties consumerProperties) {
    this.launchApplicationService = launchApplicationService;
    this.meterRegistry = meterRegistry;
    this.consumeTimer = Timer.builder(METRIC_CONSUME_DURATION).register(meterRegistry);
    this.kafkaQueueAgeTimer = Timer.builder(METRIC_KAFKA_QUEUE_AGE).register(meterRegistry);
    this.rateLimitBackoff =
        Duration.ofMillis(Math.max(1L, consumerProperties.getErrorHandler().getRetryBackoffMs()));
    this.counterCache = Caffeine.newBuilder()
        .maximumSize(MAX_COUNTER_CACHE_SIZE)
        .expireAfterAccess(Duration.ofHours(1))
        .build();
  }

  @KafkaListener(
      topics = BatchTopics.TRIGGER_LAUNCH_V1,
      groupId = "${batch.trigger.consumer.group-id:orchestrator-trigger-launch}",
      containerFactory = OrchestratorKafkaConsumerConfiguration.TRIGGER_LISTENER_FACTORY)
  public void consume(ConsumerRecord<String, String> consumerRecord, Acknowledgment ack) {
    recordKafkaQueueAge(consumerRecord);
    LaunchEnvelope envelope;
    try {
      envelope = JsonUtils.fromJson(consumerRecord.value(), LaunchEnvelope.class);
    } catch (RuntimeException ex) {
      // payload 反序列化失败 = 数据问题/协议演进未兼容;记 metric + ack 跳过,避免无限重试堆积
      log.error(
          "TriggerLaunchConsumer failed to deserialize the message; skipping: topic={} partition={} offset={} key={}",
          consumerRecord.topic(),
          consumerRecord.partition(),
          consumerRecord.offset(),
          consumerRecord.key(),
          ex);
      counter(METRIC_FAILED, "reason", "deserialize").increment();
      ack.acknowledge();
      return;
    }
    if (EmptyChecks.isNull(envelope) || EmptyChecks.isNull(envelope.launchRequest())) {
      log.warn(
          "TriggerLaunchConsumer envelope/launchRequest is null; skipping: offset={}",
          consumerRecord.offset());
      counter(METRIC_FAILED, "reason", "empty_envelope").increment();
      ack.acknowledge();
      return;
    }
    LaunchRequest request = envelope.launchRequest();
    String tenantId = EmptyChecks.isNull(request.tenantId()) ? "unknown" : request.tenantId();
    String tenantTag = normalizeTenantTag(tenantId);
    // R3-P1-2：Kafka listener 入口注入 MDC，让 launch 失败的 ERROR 日志可按 tenant/trace 过滤。
    BatchMdc.put(StructuredLogField.TENANT_ID, tenantId);
    BatchMdc.put(StructuredLogField.TRACE_ID, request.traceId());
    BatchMdc.put(StructuredLogField.REQUEST_ID, request.requestId());
    Timer.Sample consumeSample = Timer.start(meterRegistry);
    try {
      // P1 fix(be-kafka-rls):tenantId 非空且非占位时绑 RLS holder,让 biz DS 事务起点把
      // app.tenant_id 推到 PG session,触发 biz.* RLS policy。tenantId="unknown" 不绑,
      // 让 RLS Phase B 严格策略拒绝(防伪造)。
      final String boundTenantId = tenantId;
      final LaunchRequest boundRequest = request;
      LaunchResponse response;
      if (EmptyChecks.isNotBlank(boundTenantId) && !"unknown".equals(boundTenantId)) {
        response = RlsTenantContextHolder.runWithTenant(
            boundTenantId, () -> launchApplicationService.launchFromTrustedQueue(boundRequest));
      } else {
        response = launchApplicationService.launchFromTrustedQueue(boundRequest);
      }
      log.debug(
          "TriggerLaunchConsumer launch succeeded: tenantId={} requestId={} instanceNo={}",
          tenantId,
          request.requestId(),
          EmptyChecks.isNull(response) ? null : response.instanceNo());
      counter(METRIC_CONSUMED, "tenant", tenantTag, "outcome", "ok").increment();
      ack.acknowledge();
    } catch (ResponseStatusException ex) {
      if (ex.getStatusCode().value() == 409) {
        log.info(
            "TriggerLaunchConsumer duplicate requestId was deduplicated; treating it as success: tenantId={} requestId={}",
            tenantId,
            request.requestId());
        counter(METRIC_DEDUPED, "tenant", tenantTag).increment();
        ack.acknowledge();
        return;
      }
      if (ex.getStatusCode().value() == 429) {
        log.warn(
            "TriggerLaunchConsumer was rate limited; pausing the partition before redelivery: tenantId={} requestId={} backoffMs={}",
            tenantId,
            request.requestId(),
            rateLimitBackoff.toMillis());
        counter(METRIC_FAILED, "tenant", tenantTag, "reason", "rate_limited").increment();
        // 429 是容量反压，不是有限次数后可以丢弃的失败。显式 nack 会回退当前 offset 并暂停该
        // partition；正常返回可绕开 DefaultErrorHandler 的重试耗尽 recoverer。
        ack.nack(rateLimitBackoff);
        return;
      }
      counter(
              METRIC_FAILED,
              "tenant",
              tenantTag,
              "reason",
              "http_" + ex.getStatusCode().value())
          .increment();
      throw ex;
    } catch (BizException ex) {
      // 业务级拒收(jobCode 不存在 / 跨租 / 字段缺失等)— 不可恢复,重投只是无效复制。
      // 必须 ack 让 offset 前进,否则同 partition 后续合法消息全被阻塞(poison message)。
      log.warn(
          "TriggerLaunchConsumer rejected a business message (ack and drop; no retry): tenantId={} requestId={} code={} message={}",
          tenantId,
          request.requestId(),
          ex.getCode(),
          ex.getMessage());
      counter(METRIC_FAILED, "tenant", tenantTag, "reason", "business").increment();
      ack.acknowledge();
    } catch (RuntimeException ex) {
      log.error(
          "TriggerLaunchConsumer launch failed: tenantId={} requestId={}",
          tenantId,
          request.requestId(),
          ex);
      counter(METRIC_FAILED, "tenant", tenantTag, "reason", "runtime").increment();
      throw ex;
    } finally {
      consumeSample.stop(consumeTimer);
      BatchMdc.removeAll(
          StructuredLogField.TENANT_ID, StructuredLogField.TRACE_ID, StructuredLogField.REQUEST_ID);
    }
  }

  private Counter counter(String name, String... tagPairs) {
    MetricKey key = new MetricKey(name, List.copyOf(Arrays.asList(tagPairs.clone())));
    return counterCache.get(
        key, ignored -> Counter.builder(name).tags(Tags.of(tagPairs)).register(meterRegistry));
  }

  /**
   * Counter 注册本身由 MeterRegistry 去重，但每次 launch 仍会重复做名称和标签查找。
   * 这里仅缓存有限的标签组合，避免高压消费把指标管理开销叠加到业务热路径；租户标签上限仍由
   * {@link #normalizeTenantTag(String)} 控制。
   */
  private record MetricKey(String name, List<String> tagPairs) {}

  private void recordKafkaQueueAge(ConsumerRecord<String, String> consumerRecord) {
    long timestamp = consumerRecord.timestamp();
    if (timestamp > 0) {
      kafkaQueueAgeTimer.record(
          Math.max(0L, System.currentTimeMillis() - timestamp), TimeUnit.MILLISECONDS);
    }
  }
}
