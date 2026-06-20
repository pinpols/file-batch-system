package com.example.batch.orchestrator.application.service.governance;

import com.example.batch.common.enums.AlertStatus;
import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.common.utils.AlertFingerprints;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.controller.request.AlertEmitRequest;
import com.example.batch.orchestrator.mapper.AlertEventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警事件 emit 入口：把 orchestrator 内部各子系统（SLA / 熔断 / drain 等）的告警统一落 {@code alert_event} 表。
 *
 * <p>去重靠 {@code dedup_fingerprint}（{@code tenantId + alertType + resourceKey} 的哈希）—— {@code
 * insertOrMerge} 依赖 DB 唯一约束做 UPSERT，重复告警合并到同一行（更新 last_triggered_at 等）， 避免同一故障刷屏污染告警表；同时在
 * Micrometer 上打 {@code batch.alert.events} 计数便于监控。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultAlertEventService implements AlertEventService {

  private final AlertEventMapper alertEventMapper;
  private final MeterRegistry meterRegistry;

  @Override
  @Transactional
  public void emit(AlertEmitRequest request) {
    if (request == null
        || !Texts.hasText(request.tenantId())
        || !Texts.hasText(request.alertType())) {
      return;
    }
    String serviceName =
        Texts.hasText(request.serviceName()) ? request.serviceName() : "batch-orchestrator";
    String severity = Texts.hasText(request.severity()) ? request.severity() : "WARN";
    String title = Texts.hasText(request.title()) ? request.title() : request.alertType();
    String resourceKey = Texts.hasText(request.resourceKey()) ? request.resourceKey() : title;
    String fingerprint =
        AlertFingerprints.build(request.tenantId(), request.alertType(), resourceKey);

    AlertEventEntity entity = new AlertEventEntity();
    entity.setTenantId(request.tenantId());
    entity.setServiceName(serviceName);
    entity.setAlertType(request.alertType());
    entity.setSeverity(severity);
    entity.setTitle(title);
    entity.setDetailJson(request.detailJson());
    entity.setDedupFingerprint(fingerprint);
    entity.setTraceId(request.traceId());
    entity.setStatus(AlertStatus.OPEN.name());

    alertEventMapper.insertOrMerge(entity);

    meterRegistry
        .counter(
            "batch.alert.events", Tags.of("alert_type", request.alertType(), "severity", severity))
        .increment();
  }

  @Override
  @Transactional
  public int escalateOverdue(int slaMinutes, int maxTier, int batchLimit) {
    if (slaMinutes <= 0 || maxTier <= 0 || batchLimit <= 0) {
      return 0;
    }
    List<AlertEventEntity> overdue =
        alertEventMapper.selectOverdueForEscalation(slaMinutes, maxTier, batchLimit);
    int escalated = 0;
    for (AlertEventEntity alert : overdue) {
      int currentTier = alert.getEscalationTier() == null ? 0 : alert.getEscalationTier();
      int updated = alertEventMapper.markEscalated(alert.getId(), alert.getTenantId(), currentTier);
      if (updated == 0) {
        // 被并发 ack 或其它节点抢先升级,跳过。
        continue;
      }
      int newTier = currentTier + 1;
      escalated++;
      meterRegistry
          .counter(
              "batch.alert.escalations",
              Tags.of("alert_type", alert.getAlertType(), "tier", String.valueOf(newTier)))
          .increment();
      log.error(
          "Alert escalated to tier {} after staying OPEN past ack-SLA: alertId={} tenantId={} "
              + "alertType={} severity={} traceId={}",
          newTier,
          alert.getId(),
          alert.getTenantId(),
          alert.getAlertType(),
          alert.getSeverity(),
          alert.getTraceId());
    }
    return escalated;
  }
}
