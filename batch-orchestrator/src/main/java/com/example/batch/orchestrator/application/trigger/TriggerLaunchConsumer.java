package com.example.batch.orchestrator.application.trigger;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.task.LaunchApplicationService;
import com.example.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
 * <p>ADR-010 固化路径，无条件实例化（2026-05-02 同步 HTTP 路径已删除）。
 */
@Component
@Slf4j
public class TriggerLaunchConsumer {

  private static final String METRIC_CONSUMED = "batch.trigger.launch.consumed.total";
  private static final String METRIC_DEDUPED = "batch.trigger.launch.deduped.total";
  private static final String METRIC_FAILED = "batch.trigger.launch.failed.total";

  private final LaunchApplicationService launchApplicationService;
  private final MeterRegistry meterRegistry;
  // 2026-05-02: 闭环 trigger_request 状态机 — launch 成功后回写 LAUNCHED + relatedJobInstanceId
  private final TriggerRequestMapper triggerRequestMapper;
  private final JobInstanceMapper jobInstanceMapper;

  public TriggerLaunchConsumer(
      LaunchApplicationService launchApplicationService,
      MeterRegistry meterRegistry,
      TriggerRequestMapper triggerRequestMapper,
      JobInstanceMapper jobInstanceMapper) {
    this.launchApplicationService = launchApplicationService;
    this.meterRegistry = meterRegistry;
    this.triggerRequestMapper = triggerRequestMapper;
    this.jobInstanceMapper = jobInstanceMapper;
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
      // 2026-05-02 ADR-010 闭环:回写 trigger_request.LAUNCHED + relatedJobInstanceId,
      // 让审计 / SLA 报表能从 trigger_request 单表判定"job 是否真跑了"。best-effort,失败仅
      // log warn — 主路径已 ack,落地由 ad-hoc reconciler 兜底,绝不让回写失败回滚 launch。
      writeBackTriggerRequestLaunched(tenantId, request.requestId(), response);
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

  /**
   * 把 trigger_request 的 status 推到 LAUNCHED + 写回 relatedJobInstanceId。
   *
   * <p>best-effort:任何异常仅 WARN 不抛 — 主路径 launch 已成功并 ack,这一步只是审计字段闭环。失败时 trigger_request 留在 ACCEPTED
   * 状态(异步路径异常态),由后续 reconciler / 运维 SQL 兜底。
   */
  private void writeBackTriggerRequestLaunched(
      String tenantId, String requestId, LaunchResponse response) {
    if (tenantId == null || tenantId.isBlank() || requestId == null || requestId.isBlank()) {
      return;
    }
    Long jobInstanceId = null;
    try {
      if (response != null && response.instanceNo() != null && !response.instanceNo().isBlank()) {
        JobInstanceEntity jobInstance =
            jobInstanceMapper.selectByInstanceNo(tenantId, response.instanceNo());
        if (jobInstance != null) {
          jobInstanceId = jobInstance.getId();
        }
      }
      triggerRequestMapper.updateAcceptance(tenantId, requestId, "LAUNCHED", jobInstanceId);
    } catch (RuntimeException ex) {
      log.warn(
          "TriggerLaunchConsumer 回写 trigger_request LAUNCHED 失败(best-effort,主路径已 ack): tenantId={}"
              + " requestId={} error={}",
          tenantId,
          requestId,
          ex.getMessage());
    }
  }
}
