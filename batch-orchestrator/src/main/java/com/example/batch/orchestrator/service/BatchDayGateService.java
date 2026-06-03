package com.example.batch.orchestrator.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.service.governance.AlertEventService;
import com.example.batch.orchestrator.controller.request.AlertEmitRequest;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import java.time.Instant;
import java.time.LocalDate;
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
  private final JobInstanceMapper jobInstanceMapper;
  private final BatchDateTimeSupport dateTimeSupport;
  private final AlertEventService alertEventService;

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
    // FROZEN 优先于前日门闩。CATCH_UP / RERUN 显式覆盖 frozen 限制(运维补救路径)。
    if (!isFrozenBypassTrigger(request.triggerType())) {
      BatchDayInstanceEntity currentDay =
          batchDayInstanceMapper.selectByTenantCalendarBizDate(
              request.tenantId(), job.calendarCode(), request.bizDate());
      if (currentDay != null && Boolean.TRUE.equals(currentDay.frozen())) {
        String reason = "BATCH_DAY_FROZEN";
        reject(request, currentDay, reason);
        return GateDecision.reject(reason);
      }
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
    LocalDate prevBizDate = request.bizDate().minusDays(1);
    BatchDayInstanceEntity previous =
        batchDayInstanceMapper.selectByTenantCalendarBizDate(
            request.tenantId(), job.calendarCode(), prevBizDate);
    // SAME_JOB / SAME_JOB_GROUP 细粒度: 在 batch_day_instance 之外, 还要看
    // job_instance 维度的实际终态(防止 calendar 已 SETTLED 但同组某 job 上一日还在 RUNNING)。
    if ("SAME_JOB".equals(scope) || "SAME_JOB_GROUP".equals(scope)) {
      String fineReason = checkFineGrainedDependency(scope, request, job, prevBizDate);
      if (fineReason != null) {
        if (POLICY_REJECT_IF_PREVIOUS_OPEN.equals(policy)) {
          reject(request, previous, fineReason);
          return GateDecision.reject(fineReason);
        }
        waitLaunch(
            request, loaded.triggerRequest(), effectiveParams, traceId, previous, fineReason);
        return GateDecision.waiting(fineReason);
      }
      // 细粒度通过 → 不再用 batch_day_instance 维度卡住, 即使日历级是 IN_FLIGHT 也允许
      return GateDecision.allow();
    }
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

  /**
   * SAME_JOB / SAME_JOB_GROUP 细粒度检查。
   *
   * <p>非 null 返回值表示阻塞原因(同 job 或同组前一日仍有非终态实例); null 表示通过。
   */
  private String checkFineGrainedDependency(
      String scope, LaunchRequest request, JobDefinitionEntity job, LocalDate prevBizDate) {
    if ("SAME_JOB_GROUP".equals(scope) && Texts.hasText(job.jobGroupCode())) {
      int active =
          jobInstanceMapper.countNonTerminalByJobGroupAndBizDate(
              request.tenantId(), job.jobGroupCode(), prevBizDate);
      return active > 0 ? "PREVIOUS_JOB_GROUP_NOT_CLOSED" : null;
    }
    // SAME_JOB, 或 SAME_JOB_GROUP 但未配组(降级为 SAME_JOB)
    int active =
        jobInstanceMapper.countNonTerminalByJobCodeAndBizDate(
            request.tenantId(), request.jobCode(), prevBizDate);
    return active > 0 ? "PREVIOUS_JOB_NOT_CLOSED" : null;
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
    Instant now = dateTimeSupport.nowInstant();
    String payload = buildLaunchPayload(request, effectiveParams);
    BatchDayWaitingLaunchEntity waiting =
        BatchDayWaitingLaunchEntity.builder()
            .tenantId(request.tenantId())
            .calendarCode(previous.calendarCode())
            .jobCode(request.jobCode())
            .bizDate(request.bizDate())
            .requestId(request.requestId())
            .traceId(traceId)
            .triggerType(request.triggerType() == null ? null : request.triggerType().code())
            .waitReason(reason)
            .launchPayload(payload)
            .waitStatus(BatchStatusConstants.WAITING)
            .createdAt(now)
            .updatedAt(now)
            .build();
    waitingLaunchMapper.insert(waiting);
    triggerRequestMapper.updateAcceptance(
        request.tenantId(), request.requestId(), BatchStatusConstants.WAITING, null);
    appendAuditLog(request, previous, "BATCH_DAY_GATE_WAIT", reason, traceId);
    emitGateAlert(request, previous, "BATCH_DAY_GATE_WAITING", "WARN", reason, traceId);
  }

  private void reject(LaunchRequest request, BatchDayInstanceEntity previous, String reason) {
    triggerRequestMapper.updateAcceptance(
        request.tenantId(), request.requestId(), BatchStatusConstants.REJECTED, null);
    appendAuditLog(request, previous, "BATCH_DAY_GATE_REJECT", reason, request.traceId());
    emitGateAlert(request, previous, "BATCH_DAY_GATE_REJECTED", "ERROR", reason, request.traceId());
  }

  private void emitGateAlert(
      LaunchRequest request,
      BatchDayInstanceEntity previous,
      String alertType,
      String severity,
      String reason,
      String traceId) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("tenantId", request.tenantId());
    detail.put("jobCode", request.jobCode());
    detail.put("bizDate", request.bizDate() == null ? null : request.bizDate().toString());
    detail.put("requestId", request.requestId());
    detail.put("triggerType", request.triggerType() == null ? null : request.triggerType().code());
    detail.put("reasonCode", reason);
    if (previous != null) {
      detail.put("calendarCode", previous.calendarCode());
      detail.put(
          "previousBizDate", previous.bizDate() == null ? null : previous.bizDate().toString());
      detail.put("previousDayStatus", previous.dayStatus());
    }
    String resourceKey =
        request.tenantId()
            + ":"
            + (previous == null ? "" : previous.calendarCode() + ":")
            + request.jobCode()
            + ":"
            + (request.bizDate() == null ? "" : request.bizDate());
    AlertEmitRequest emitRequest =
        AlertEmitRequest.builder()
            .tenantId(request.tenantId())
            .serviceName("batch-orchestrator")
            .alertType(alertType)
            .severity(severity)
            .title("batch day gate " + alertType.toLowerCase(Locale.ROOT) + ": " + reason)
            .detailJson(JsonUtils.toJson(detail))
            .resourceKey(resourceKey)
            .traceId(traceId)
            .build();
    alertEventService.emit(emitRequest);
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

  private boolean isFrozenBypassTrigger(TriggerType triggerType) {
    return triggerType == TriggerType.CATCH_UP || triggerType == TriggerType.RERUN;
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
