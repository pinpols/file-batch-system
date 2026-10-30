package io.github.pinpols.batch.trigger.infrastructure.readiness;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * readiness 等待移交给 Orchestrator 的客户端。
 *
 * <p>Trigger 在 defer 后把原依赖、版本、业务日与截止时间移交给 Orchestrator；移交失败只告警，不阻断 Trigger
 * 自身的等待与兜底扫尾（Phase 1 能力保留），但会丢失"无 Quartz 回调也能恢复"的接管路径，故记 WARN 供运维发现。
 */
@Slf4j
@Component
public class ReadinessWaitHandoverClient {

  private static final String METRIC_HANDOVER_FAILED =
      "batch.trigger.readiness.handover.failed.total";
  private static final String METRIC_CLOSE_FAILED = "batch.trigger.readiness.close.failed.total";

  private final RestClient orchestratorRestClient;
  private final MeterRegistry meterRegistry;

  public ReadinessWaitHandoverClient(
      RestClient orchestratorRestClient, MeterRegistry meterRegistry) {
    this.orchestratorRestClient = orchestratorRestClient;
    this.meterRegistry = meterRegistry;
  }

  public void handover(
      TriggerDescriptor descriptor,
      Instant scheduledFireTime,
      LocalDate bizDate,
      Instant deferredSince,
      Instant deadline) {
    if (EmptyChecks.isNull(descriptor)
        || EmptyChecks.isNull(scheduledFireTime)
        || EmptyChecks.isNull(deadline)) {
      return;
    }
    Map<String, Object> body = new HashMap<>();
    body.put("tenantId", descriptor.getTenantId());
    body.put("workflowDefinitionId", descriptor.getWorkflowDefinitionId());
    body.put("workflowCode", descriptor.getWorkflowCode());
    body.put("calendarCode", descriptor.getCalendarCode());
    body.put("scheduleVersion", descriptor.getWorkflowVersion());
    body.put("dependsOnJobCodes", resolveDependsOnJobCodes(descriptor));
    body.put("bizDate", EmptyChecks.isNull(bizDate) ? null : bizDate.toString());
    body.put("scheduledFireTime", scheduledFireTime.toString());
    body.put("deferredSince", EmptyChecks.isNull(deferredSince) ? null : deferredSince.toString());
    body.put("deadline", deadline.toString());
    try {
      orchestratorRestClient
          .post()
          .uri("/internal/readiness-waits")
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException ex) {
      meterRegistry.counter(METRIC_HANDOVER_FAILED).increment();
      log.warn(
          "readiness wait handover failed; Orchestrator takeover unavailable for this fire:"
              + " tenantId={}, workflowDefinitionId={}, scheduledFireTime={}, cause={}",
          descriptor.getTenantId(),
          descriptor.getWorkflowDefinitionId(),
          scheduledFireTime,
          SwallowedExceptionLogger.summary(ex));
    }
  }

  /** 正常 fire 成功后取消 Orchestrator 侧等待，避免重复恢复启动。 */
  public void close(String tenantId, Long workflowDefinitionId, Instant scheduledFireTime) {
    if (EmptyChecks.isNull(workflowDefinitionId) || EmptyChecks.isNull(scheduledFireTime)) {
      return;
    }
    try {
      orchestratorRestClient
          .post()
          .uri("/internal/readiness-waits/close")
          .body(Map.of(
              "tenantId", tenantId,
              "workflowDefinitionId", workflowDefinitionId,
              "scheduledFireTime", scheduledFireTime.toString()))
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException ex) {
      meterRegistry.counter(METRIC_CLOSE_FAILED).increment();
      log.warn(
          "readiness wait close failed; Orchestrator may drive one extra recovery attempt:"
              + " tenantId={}, workflowDefinitionId={}, scheduledFireTime={}, cause={}",
          tenantId,
          workflowDefinitionId,
          scheduledFireTime,
          SwallowedExceptionLogger.summary(ex));
    }
  }

  /** 移交全部依赖：Orchestrator 接管后必须与 Trigger 主就绪闸口径一致。 */
  private List<String> resolveDependsOnJobCodes(TriggerDescriptor descriptor) {
    if (EmptyChecks.isNotEmpty(descriptor.getDependsOnJobCodes())) {
      return descriptor.getDependsOnJobCodes().stream()
          .filter(Texts::hasText)
          .map(String::trim)
          .distinct()
          .toList();
    }
    if (EmptyChecks.isNull(descriptor.getDependsOnJobCode())) {
      return List.of();
    }
    return List.of(descriptor.getDependsOnJobCode().trim());
  }
}
