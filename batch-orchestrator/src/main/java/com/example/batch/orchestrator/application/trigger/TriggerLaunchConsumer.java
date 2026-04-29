package com.example.batch.orchestrator.application.trigger;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.LaunchApplicationService;
import com.example.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-010 Stage 4: 消费 batch.trigger.launch.v1 topic,把 envelope 反序列化后调用现有 {@link
 * LaunchApplicationService#launch(LaunchRequest)} 内部 API。
 *
 * <p>幂等保证:同 requestId 多次消费 → orchestrator 端 {@code uk_job_instance_tenant_dedup} 兜底,不会真正双跑。重复消费时
 * launch 抛 CONFLICT(409), 我们视为成功 ack(消息已被处理过,不需要重投)。
 *
 * <p>失败处理:
 *
 * <ul>
 *   <li>反序列化失败 → 记录 DLQ counter + 不 ack(实际行为靠 listener 容器配置;当前不配 DLQ topic, Spring Kafka 默认会
 *       SeekToCurrentErrorHandler 重试,几次后跳过,日志为权威)
 *   <li>launch 业务异常 → 抛出,由 listener 容器决定重试;最终 ack 失败的消息保留在 topic
 *   <li>其他 unchecked → 同上
 * </ul>
 *
 * <p>仅当 {@code batch.trigger.async-launch.enabled=true} 时实例化(两边开关一致避免单边激活)。
 */
@Component
@Slf4j
@ConditionalOnProperty(
    prefix = "batch.trigger.async-launch",
    name = "enabled",
    havingValue = "true")
public class TriggerLaunchConsumer {

  private static final String METRIC_CONSUMED = "batch.trigger.launch.consumed.total";
  private static final String METRIC_DEDUPED = "batch.trigger.launch.deduped.total";
  private static final String METRIC_FAILED = "batch.trigger.launch.failed.total";

  private final LaunchApplicationService launchApplicationService;
  private final MeterRegistry meterRegistry;

  public TriggerLaunchConsumer(
      LaunchApplicationService launchApplicationService, MeterRegistry meterRegistry) {
    this.launchApplicationService = launchApplicationService;
    this.meterRegistry = meterRegistry;
  }

  @KafkaListener(
      topics = BatchTopics.TRIGGER_LAUNCH_V1,
      groupId = "${batch.trigger.consumer.group-id:orchestrator-trigger-launch}",
      containerFactory = OrchestratorKafkaConsumerConfiguration.TRIGGER_LISTENER_FACTORY)
  public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
    LaunchEnvelope envelope;
    try {
      envelope = JsonUtils.fromJson(record.value(), LaunchEnvelope.class);
    } catch (RuntimeException ex) {
      // payload 反序列化失败 = 数据问题/协议演进未兼容;记 metric + ack 跳过,避免无限重试堆积
      log.error(
          "TriggerLaunchConsumer 反序列化失败,跳过: topic={} partition={} offset={} key={}",
          record.topic(),
          record.partition(),
          record.offset(),
          record.key(),
          ex);
      counter(METRIC_FAILED, "reason", "deserialize").increment();
      ack.acknowledge();
      return;
    }
    if (envelope == null || envelope.launchRequest() == null) {
      log.warn(
          "TriggerLaunchConsumer envelope/launchRequest 为 null,跳过: offset={}", record.offset());
      counter(METRIC_FAILED, "reason", "empty_envelope").increment();
      ack.acknowledge();
      return;
    }
    LaunchRequest request = envelope.launchRequest();
    String tenantId = request.tenantId() == null ? "unknown" : request.tenantId();
    try {
      LaunchResponse response = launchApplicationService.launch(request);
      log.info(
          "TriggerLaunchConsumer launch 成功: tenantId={} requestId={} instanceNo={}",
          tenantId,
          request.requestId(),
          response == null ? null : response.instanceNo());
      counter(METRIC_CONSUMED, "tenant", tenantId, "outcome", "ok").increment();
      ack.acknowledge();
    } catch (ResponseStatusException ex) {
      // 409 = uk_job_instance_tenant_dedup 兜底命中,视为已处理(同 requestId 已落库一次)
      if (ex.getStatusCode().value() == 409) {
        log.info(
            "TriggerLaunchConsumer 重复 requestId 被 dedup 兜底,视为成功: tenantId={} requestId={}",
            tenantId,
            request.requestId());
        counter(METRIC_DEDUPED, "tenant", tenantId).increment();
        ack.acknowledge();
        return;
      }
      // 429 = 限流;记失败但仍 ack 避免无限重投阻塞 partition,等下次 trigger 上报时由 outbox 重发
      if (ex.getStatusCode().value() == 429) {
        log.warn(
            "TriggerLaunchConsumer 限流被拒,ack 跳过(由 trigger outbox 重发): tenantId={} requestId={}",
            tenantId,
            request.requestId());
        counter(METRIC_FAILED, "reason", "rate_limited").increment();
        ack.acknowledge();
        return;
      }
      // 其它 5xx / 4xx 异常 → 抛出走 listener container 重试
      counter(METRIC_FAILED, "reason", "http_" + ex.getStatusCode().value()).increment();
      throw ex;
    } catch (RuntimeException ex) {
      log.error(
          "TriggerLaunchConsumer launch 失败: tenantId={} requestId={}",
          tenantId,
          request.requestId(),
          ex);
      counter(METRIC_FAILED, "reason", "runtime").increment();
      // 抛出异常,Spring Kafka SeekToCurrentErrorHandler 默认重试
      throw ex;
    }
  }

  private Counter counter(String name, String... tagPairs) {
    return Counter.builder(name).tags(Tags.of(tagPairs)).register(meterRegistry);
  }
}
