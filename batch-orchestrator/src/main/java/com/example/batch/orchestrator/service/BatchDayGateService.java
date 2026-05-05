package com.example.batch.orchestrator.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BatchDayGateService {

  private static final String POLICY_ALLOW_OVERLAP = "ALLOW_OVERLAP";
  private static final String POLICY_WAIT_PREVIOUS_DAY = "WAIT_PREVIOUS_DAY";
  private static final String POLICY_REJECT_IF_PREVIOUS_OPEN = "REJECT_IF_PREVIOUS_OPEN";

  private final OrchestratorConfigCacheService configCacheService;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final BatchDayWaitingLaunchMapper waitingLaunchMapper;
  private final TriggerRequestMapper triggerRequestMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;

  public GateDecision evaluateAndApply(
      LaunchRequest request,
      LaunchValidationService.LaunchLoadResult loaded,
      Map<String, Object> effectiveParams,
      String traceId) {
    if (request == null || loaded == null || loaded.jobDefinition() == null) {
      return GateDecision.allow();
    }
    JobDefinitionEntity job = loaded.jobDefinition();
    if (!Texts.hasText(job.calendarCode()) || request.bizDate() == null) {
      return GateDecision.allow();
    }
    String scope = normalize(job.previousDayDependencyScope(), "INHERIT");
    if ("NONE".equals(scope)) {
      return GateDecision.allow();
    }
    BusinessCalendarEntity calendar =
        configCacheService.findEnabledBusinessCalendar(request.tenantId(), job.calendarCode());
    String policy =
        "INHERIT".equals(scope)
            ? normalize(
                calendar == null ? null : calendar.dayRolloverPolicy(), POLICY_ALLOW_OVERLAP)
            : POLICY_WAIT_PREVIOUS_DAY;
    if (POLICY_ALLOW_OVERLAP.equals(policy)) {
      return GateDecision.allow();
    }
    BatchDayInstanceEntity previous =
        batchDayInstanceMapper.selectByTenantCalendarBizDate(
            request.tenantId(), job.calendarCode(), request.bizDate().minusDays(1));
    if (previous == null || isPreviousDayReleasable(previous.dayStatus())) {
      return GateDecision.allow();
    }
    String reason = "PREVIOUS_BATCH_DAY_NOT_CLOSED";
    if (POLICY_REJECT_IF_PREVIOUS_OPEN.equals(policy)) {
      reject(request, previous, reason);
      return GateDecision.reject(reason);
    }
    waitLaunch(request, loaded.triggerRequest(), effectiveParams, traceId, previous, reason);
    return GateDecision.waiting(reason);
  }

  private boolean isPreviousDayReleasable(String status) {
    String normalized = normalize(status, "");
    return "SETTLED".equals(normalized)
        || "SKIPPED".equals(normalized)
        || "MANUAL_RELEASED".equals(normalized);
  }

  private void waitLaunch(
      LaunchRequest request,
      TriggerRequestEntity triggerRequest,
      Map<String, Object> effectiveParams,
      String traceId,
      BatchDayInstanceEntity previous,
      String reason) {
    Instant now = Instant.now();
    String payload = buildLaunchPayload(request, effectiveParams);
    waitingLaunchMapper.insert(
        new BatchDayWaitingLaunchEntity(
            null,
            request.tenantId(),
            previous.calendarCode(),
            request.jobCode(),
            request.bizDate(),
            request.requestId(),
            traceId,
            request.triggerType() == null ? null : request.triggerType().code(),
            reason,
            payload,
            BatchStatusConstants.WAITING,
            null,
            null,
            now,
            now));
    triggerRequestMapper.updateAcceptance(
        request.tenantId(), request.requestId(), BatchStatusConstants.WAITING, null);
    appendAuditLog(request, previous, "BATCH_DAY_GATE_WAIT", reason, traceId);
  }

  private void reject(LaunchRequest request, BatchDayInstanceEntity previous, String reason) {
    triggerRequestMapper.updateAcceptance(
        request.tenantId(), request.requestId(), BatchStatusConstants.REJECTED, null);
    appendAuditLog(request, previous, "BATCH_DAY_GATE_REJECT", reason, request.traceId());
  }

  private String buildLaunchPayload(LaunchRequest request, Map<String, Object> effectiveParams) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", request.tenantId());
    payload.put("jobCode", request.jobCode());
    payload.put("bizDate", request.bizDate() == null ? null : request.bizDate().toString());
    payload.put("triggerType", request.triggerType() == null ? null : request.triggerType().code());
    payload.put("requestId", request.requestId());
    payload.put("traceId", request.traceId());
    payload.put("params", request.params());
    payload.put("effectiveParams", effectiveParams);
    payload.put(
        "dataIntervalStart",
        request.dataIntervalStart() == null ? null : request.dataIntervalStart().toString());
    payload.put(
        "dataIntervalEnd",
        request.dataIntervalEnd() == null ? null : request.dataIntervalEnd().toString());
    return JsonUtils.toJson(payload);
  }

  private void appendAuditLog(
      LaunchRequest request,
      BatchDayInstanceEntity previous,
      String operation,
      String reason,
      String traceId) {
    JobExecutionLogEntity audit = new JobExecutionLogEntity();
    audit.setTenantId(request.tenantId());
    audit.setJobInstanceId(null);
    audit.setJobPartitionId(null);
    audit.setLogLevel("INFO");
    audit.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
    audit.setTraceId(traceId);
    audit.setMessage(operation);
    audit.setDetailRef(AuditLogConstants.DETAIL_REF_BATCH_DAY_INSTANCE);
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("jobCode", request.jobCode());
    extra.put("bizDate", request.bizDate() == null ? null : request.bizDate().toString());
    extra.put("previousBizDate", previous.bizDate() == null ? null : previous.bizDate().toString());
    extra.put("calendarCode", previous.calendarCode());
    extra.put("previousDayStatus", previous.dayStatus());
    extra.put("reasonCode", reason);
    extra.put("operatorId", AuditLogConstants.OPERATOR_ID_SYSTEM);
    extra.put("operatorType", AuditLogConstants.OPERATOR_TYPE_SYSTEM);
    audit.setExtraJson(JsonUtils.toJson(extra));
    jobExecutionLogMapper.insert(audit);
  }

  private String normalize(String value, String defaultValue) {
    return Texts.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : defaultValue;
  }

  public record GateDecision(GateDecisionType type, String reasonCode) {
    static GateDecision allow() {
      return new GateDecision(GateDecisionType.ALLOW, null);
    }

    static GateDecision waiting(String reasonCode) {
      return new GateDecision(GateDecisionType.WAIT, reasonCode);
    }

    static GateDecision reject(String reasonCode) {
      return new GateDecision(GateDecisionType.REJECT, reasonCode);
    }

    boolean allowed() {
      return type == GateDecisionType.ALLOW;
    }
  }

  public enum GateDecisionType {
    ALLOW,
    WAIT,
    REJECT
  }
}
