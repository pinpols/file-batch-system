package io.github.pinpols.batch.orchestrator.application.trigger;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>幂等保证:同 requestId 多次消费 → orchestrator 端 {@code uk_job_instance_tenant_dedup} 回退,不会真正双跑。重复消费时
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

  // R3-P0-11：Kafka 消息可注入任意 tenantId 字符串 → Prometheus TSDB cardinality 爆失败。
  // 用 ConcurrentHashMap.newKeySet 缓存已观测 tenantId，超过阈值后新 tenant 统一归一化为 "other"。
  // 256 是 Prom 单 metric 系列上限的实用阈值（足够区分常见租户 + 容忍误差）。
  private static final int MAX_TENANT_TAG_CARDINALITY = 256;
  private static final Set<String> OBSERVED_TENANTS = ConcurrentHashMap.newKeySet();

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
          "TriggerLaunchConsumer failed to deserialize the message; skipping: topic={} partition={} offset={} key={}",
          record.topic(),
          record.partition(),
          record.offset(),
          record.key(),
          ex);
      counter(METRIC_FAILED, "reason", "deserialize").increment();
      ack.acknowledge();
      return;
    }
    if (EmptyChecks.isNull(envelope) || EmptyChecks.isNull(envelope.launchRequest())) {
      log.warn(
          "TriggerLaunchConsumer envelope/launchRequest is null; skipping: offset={}",
          record.offset());
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
    try {
      // P1 fix(be-kafka-rls):tenantId 非空且非占位时绑 RLS holder,让 biz DS 事务起点把
      // app.tenant_id 推到 PG session,触发 biz.* RLS policy。tenantId="unknown" 不绑,
      // 让 RLS Phase B 严格策略拒绝(防伪造)。
      final String boundTenantId = tenantId;
      final LaunchRequest boundRequest = request;
      LaunchResponse response;
      if (EmptyChecks.isNotBlank(boundTenantId) && !"unknown".equals(boundTenantId)) {
        response = RlsTenantContextHolder.runWithTenant(boundTenantId, () -> {
          LaunchResponse r = launchApplicationService.launch(boundRequest);
          // 闭环回写也在 holder 作用域内,确保 trigger_request UPDATE 走 RLS。
          writeBackTriggerRequestLaunched(boundTenantId, boundRequest.requestId(), r);
          return r;
        });
      } else {
        response = launchApplicationService.launch(boundRequest);
        writeBackTriggerRequestLaunched(boundTenantId, boundRequest.requestId(), response);
      }
      log.info(
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
            "TriggerLaunchConsumer was rate limited; leaving the message unacknowledged for Kafka retry: tenantId={} requestId={}",
            tenantId,
            request.requestId());
        counter(METRIC_FAILED, "tenant", tenantTag, "reason", "rate_limited").increment();
        throw ex;
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
      BatchMdc.removeAll(
          StructuredLogField.TENANT_ID, StructuredLogField.TRACE_ID, StructuredLogField.REQUEST_ID);
    }
  }

  private Counter counter(String name, String... tagPairs) {
    return Counter.builder(name).tags(Tags.of(tagPairs)).register(meterRegistry);
  }

  /**
   * 把 trigger_request 的 status 推到 LAUNCHED + 写回 relatedJobInstanceId。
   *
   * <p>best-effort:任何异常仅 WARN 不抛 — 主路径 launch 已成功并 ack,这一步只是审计字段闭环。失败时 trigger_request 留在 ACCEPTED
   * 状态(异步路径异常态),由后续 reconciler / 运维 SQL 回退。
   */
  private void writeBackTriggerRequestLaunched(
      String tenantId, String requestId, LaunchResponse response) {
    if (EmptyChecks.isBlank(tenantId) || EmptyChecks.isBlank(requestId)) {
      return;
    }
    Long jobInstanceId = null;
    try {
      if (EmptyChecks.isNotNull(response)
          && EmptyChecks.isNotNull(response.instanceNo())
          && EmptyChecks.isNotBlank(response.instanceNo())) {
        JobInstanceEntity jobInstance =
            jobInstanceMapper.selectByInstanceNo(tenantId, response.instanceNo());
        if (EmptyChecks.isNotNull(jobInstance)) {
          jobInstanceId = jobInstance.getId();
        }
      }
      int updated = triggerRequestMapper.updateAcceptance(
          tenantId, requestId, TriggerRequestStatus.LAUNCHED.code(), jobInstanceId);
      if (updated == 0) {
        log.warn(
            "TriggerLaunchConsumer updateAcceptance(LAUNCHED) affected 0 rows; the row is terminal or was taken over by another path:"
                + " tenantId={} requestId={}",
            tenantId,
            requestId);
      }
    } catch (RuntimeException ex) {
      log.warn(
          "TriggerLaunchConsumer failed to update trigger_request to LAUNCHED (best effort; main path already acked): tenantId={}"
              + " requestId={} error={}",
          tenantId,
          requestId,
          ex.getMessage());
    }
  }
}
