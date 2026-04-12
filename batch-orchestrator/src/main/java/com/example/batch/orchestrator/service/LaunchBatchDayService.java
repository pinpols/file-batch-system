package com.example.batch.orchestrator.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.OrchestratorJobMappers;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceRecord;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarRecord;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.repository.BatchDayInstanceRepository;
import com.example.batch.orchestrator.service.LaunchValidationService.LaunchLoadResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 批次日（Batch Day）生命周期管理：upsert、cutoff 判定、late arrival 路由、审计日志。 */
@Service
@RequiredArgsConstructor
public class LaunchBatchDayService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String REASON_LATE_ACCEPTED = "LATE_ACCEPTED";

  private final OrchestratorConfigCacheService configCacheService;
  private final BatchDayInstanceRepository batchDayInstanceRepository;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final OrchestratorJobMappers jobMappers;

  void upsertBatchDayInstance(
      LaunchRequest request,
      JobDefinitionRecord jobDefinition,
      Map<String, Object> effectiveParams,
      Instant batchDaySlaDeadlineAt) {
    if (isMissingLaunchContext(request, jobDefinition)) {
      return;
    }
    String calendarCode = LaunchParamResolver.textValue(jobDefinition.calendarCode());
    if (!StringUtils.hasText(calendarCode)) {
      return;
    }
    Instant now = Instant.now();
    BatchDayInstanceRecord existing =
        batchDayInstanceRepository.findFirstByTenantIdAndCalendarCodeAndBizDate(
            request.tenantId(), calendarCode, request.bizDate());
    Instant cutoffAt = resolveBatchDayCutoffAt(request.tenantId(), calendarCode, request.bizDate());
    String operatorId = LaunchParamResolver.resolveOperatorId(effectiveParams);
    String auditOperatorId =
        StringUtils.hasText(operatorId) ? operatorId : AuditLogConstants.OPERATOR_ID_SYSTEM;
    String auditOperatorType =
        StringUtils.hasText(operatorId)
            ? AuditLogConstants.OPERATOR_TYPE_REQUEST
            : AuditLogConstants.OPERATOR_TYPE_SYSTEM;
    if (existing == null) {
      boolean catchUpLaunch = isCatchUpLaunch(request);
      boolean lateAccepted = isLateAccepted(effectiveParams);
      boolean pastCutoff = cutoffAt != null && !now.isBefore(cutoffAt);
      String dayStatus =
          (catchUpLaunch || lateAccepted) ? "IN_FLIGHT" : (pastCutoff ? "CUTOFF" : "OPEN");
      String reasonCode =
          (catchUpLaunch || lateAccepted)
              ? (lateAccepted ? REASON_LATE_ACCEPTED : "CATCH_UP_LAUNCHED")
              : (pastCutoff ? "CUTOFF_REACHED_ON_CREATE" : "BATCH_DAY_OPENED");
      batchDayInstanceRepository.save(
          new BatchDayInstanceRecord(
              null,
              request.tenantId(),
              calendarCode,
              request.bizDate(),
              dayStatus,
              now,
              cutoffAt,
              null,
              batchDaySlaDeadlineAt,
              lateAccepted ? 1 : 0,
              catchUpLaunch ? 1 : 0,
              now,
              now));
      appendBatchDayAuditLog(
          new BatchDayAuditLogParam(
              request.tenantId(),
              request.traceId(),
              null,
              dayStatus,
              calendarCode,
              request.bizDate(),
              reasonCode,
              auditOperatorId,
              auditOperatorType,
              lateAccepted ? 1 : 0,
              catchUpLaunch ? 1 : 0,
              cutoffAt));
      return;
    }
    boolean lateAccepted = isLateAccepted(effectiveParams);
    boolean catchUpLaunch = isCatchUpLaunch(request);
    boolean pastCutoff = cutoffAt != null && !now.isBefore(cutoffAt);
    boolean shouldMoveToCutoff = "OPEN".equalsIgnoreCase(existing.dayStatus()) && pastCutoff;
    boolean shouldReopen =
        shouldReopenBatchDay(existing.dayStatus()) || lateAccepted || catchUpLaunch;
    BatchDayInstanceRecord updated = existing;
    boolean changed = false;
    String fromDayStatus = existing.dayStatus();
    String toDayStatus = existing.dayStatus();
    String reasonCode = null;
    if (updated.slaDeadlineAt() == null && batchDaySlaDeadlineAt != null) {
      updated = updated.withSlaDeadlineAt(batchDaySlaDeadlineAt, now);
      changed = true;
    }
    if (updated.cutoffAt() == null && cutoffAt != null) {
      updated = updated.withCutoffAt(cutoffAt, now);
      changed = true;
    }
    if (shouldMoveToCutoff && !lateAccepted && !catchUpLaunch) {
      updated = updated.withDayStatus("CUTOFF", now);
      changed = true;
      reasonCode = "CUTOFF_REACHED";
    }
    if (lateAccepted) {
      updated = updated.withLateCount(LaunchParamResolver.safeIncrement(updated.lateCount()), now);
      changed = true;
      reasonCode = REASON_LATE_ACCEPTED;
    }
    if (catchUpLaunch) {
      updated =
          updated.withCatchupCount(LaunchParamResolver.safeIncrement(updated.catchupCount()), now);
      changed = true;
      reasonCode = "CATCH_UP_LAUNCHED";
    }
    if (shouldReopen) {
      updated = updated.withReopened(now);
      changed = true;
      reasonCode =
          lateAccepted
              ? "LATE_ACCEPTED_REOPEN"
              : (catchUpLaunch ? "CATCH_UP_REOPEN" : "BATCH_DAY_REOPENED");
    }
    if (!changed) {
      return;
    }
    toDayStatus = updated.dayStatus();
    batchDayInstanceRepository.save(updated);
    appendBatchDayAuditLog(
        new BatchDayAuditLogParam(
            request.tenantId(),
            request.traceId(),
            fromDayStatus,
            toDayStatus,
            calendarCode,
            request.bizDate(),
            reasonCode == null ? "BATCH_DAY_UPDATED" : reasonCode,
            auditOperatorId,
            auditOperatorType,
            updated.lateCount(),
            updated.catchupCount(),
            cutoffAt));
  }

  Instant resolveBatchDayCutoffAt(String tenantId, String calendarCode, LocalDate bizDate) {
    BusinessCalendarRecord calendar =
        configCacheService.findEnabledBusinessCalendar(tenantId, calendarCode);
    if (calendar == null) {
      return null;
    }
    LocalTime cutoffTime =
        calendar.cutoffTime() == null ? LocalTime.of(6, 0) : calendar.cutoffTime();
    ZoneId zoneId =
        StringUtils.hasText(calendar.timezone())
            ? ZoneId.of(calendar.timezone())
            : ZoneId.systemDefault();
    return bizDate.plusDays(1).atTime(cutoffTime).atZone(zoneId).toInstant();
  }

  Instant resolveBatchDaySlaDeadlineAt(String tenantId, String calendarCode, LocalDate bizDate) {
    BusinessCalendarRecord calendar =
        configCacheService.findEnabledBusinessCalendar(tenantId, calendarCode);
    if (!hasValidSlaOffset(calendar)) {
      return null;
    }
    LocalTime cutoffTime =
        calendar.cutoffTime() == null ? LocalTime.of(6, 0) : calendar.cutoffTime();
    ZoneId zoneId =
        StringUtils.hasText(calendar.timezone())
            ? ZoneId.of(calendar.timezone())
            : ZoneId.systemDefault();
    Instant cutoffAt = bizDate.plusDays(1).atTime(cutoffTime).atZone(zoneId).toInstant();
    return cutoffAt.plusSeconds(calendar.slaOffsetMin() * 60L);
  }

  boolean shouldReopenBatchDay(String dayStatus) {
    return "FAILED".equalsIgnoreCase(dayStatus) || "SETTLED".equalsIgnoreCase(dayStatus);
  }

  boolean isPastBatchDayCutoff(BatchDayInstanceRecord batchDay, String calendarCode) {
    if (isIncompleteBatchDay(batchDay)) {
      return false;
    }
    Instant cutoffAt = batchDay.cutoffAt();
    if (cutoffAt == null) {
      cutoffAt = resolveBatchDayCutoffAt(batchDay.tenantId(), calendarCode, batchDay.bizDate());
    }
    return cutoffAt != null && !Instant.now().isBefore(cutoffAt);
  }

  boolean isWithinLateArrivalTolerance(BatchDayInstanceRecord batchDay, String calendarCode) {
    if (batchDay == null || !StringUtils.hasText(calendarCode)) {
      return false;
    }
    Instant cutoffAt = batchDay.cutoffAt();
    if (cutoffAt == null) {
      cutoffAt = resolveBatchDayCutoffAt(batchDay.tenantId(), calendarCode, batchDay.bizDate());
    }
    if (cutoffAt == null) {
      return false;
    }
    Instant cutoffCloseAt =
        cutoffAt.plusSeconds(
            Math.max(0, resolveLateArrivalToleranceMin(batchDay.tenantId(), calendarCode)) * 60L);
    return !Instant.now().isAfter(cutoffCloseAt);
  }

  Integer resolveLateArrivalToleranceMin(String tenantId, String calendarCode) {
    BusinessCalendarRecord calendar =
        configCacheService.findEnabledBusinessCalendar(tenantId, calendarCode);
    if (!hasValidLateArrivalTolerance(calendar)) {
      return 0;
    }
    return calendar.lateArrivalToleranceMin();
  }

  private boolean isMissingLaunchContext(LaunchRequest request, JobDefinitionRecord jobDefinition) {
    return request == null || request.bizDate() == null || jobDefinition == null;
  }

  private boolean isIncompleteBatchDay(BatchDayInstanceRecord batchDay) {
    return batchDay == null || batchDay.bizDate() == null || batchDay.tenantId() == null;
  }

  private boolean hasValidSlaOffset(BusinessCalendarRecord calendar) {
    return calendar != null && calendar.slaOffsetMin() != null && calendar.slaOffsetMin() > 0;
  }

  private boolean hasValidLateArrivalTolerance(BusinessCalendarRecord calendar) {
    return calendar != null
        && calendar.lateArrivalToleranceMin() != null
        && calendar.lateArrivalToleranceMin() >= 0;
  }

  boolean isLateAccepted(Map<String, Object> params) {
    if (params == null) {
      return false;
    }
    Object lateArrival = params.get("lateArrival");
    Object arrivalStatus = params.get("arrivalStatus");
    return LaunchParamResolver.toBoolean(lateArrival)
        && REASON_LATE_ACCEPTED.equalsIgnoreCase(LaunchParamResolver.textValue(arrivalStatus));
  }

  boolean isCatchUpLaunch(LaunchRequest request) {
    return request != null && TriggerType.CATCH_UP == request.triggerType();
  }

  LaunchRequest routeLateArrivalIfNeeded(LaunchRequest request, LaunchLoadResult loaded) {
    if (request == null
        || request.triggerType() != TriggerType.EVENT
        || loaded == null
        || loaded.jobDefinition() == null
        || request.bizDate() == null) {
      return request;
    }
    String calendarCode = LaunchParamResolver.textValue(loaded.jobDefinition().calendarCode());
    if (!StringUtils.hasText(calendarCode)) {
      return request;
    }
    BatchDayInstanceRecord batchDay =
        batchDayInstanceRepository.findFirstByTenantIdAndCalendarCodeAndBizDate(
            request.tenantId(), calendarCode, request.bizDate());
    if (batchDay == null || batchDay.dayStatus() == null) {
      return request;
    }
    String dayStatus = batchDay.dayStatus();
    boolean pastCutoff = isPastBatchDayCutoff(batchDay, calendarCode);
    if ("IN_FLIGHT".equalsIgnoreCase(dayStatus)
        || ("OPEN".equalsIgnoreCase(dayStatus) && !pastCutoff)) {
      return request;
    }

    boolean treatAsCutoff = "CUTOFF".equalsIgnoreCase(dayStatus) || pastCutoff;
    boolean lateAccepted = treatAsCutoff && isWithinLateArrivalTolerance(batchDay, calendarCode);
    Map<String, Object> routedParams = new LinkedHashMap<>();
    if (request.params() != null) {
      routedParams.putAll(request.params());
    }
    routedParams.put("lateArrival", true);
    routedParams.put("arrivalStatus", lateAccepted ? REASON_LATE_ACCEPTED : "LATE_REJECTED");
    routedParams.put("batchDayStatus", dayStatus);
    if (batchDay.cutoffAt() != null) {
      routedParams.put("batchDayCutoffAt", batchDay.cutoffAt().toString());
    }
    if (lateAccepted) {
      routedParams.put(
          "lateArrivalToleranceMin",
          resolveLateArrivalToleranceMin(request.tenantId(), calendarCode));
    } else {
      routedParams.put("catchUpReason", "LATE_ARRIVAL_OR_CLOSED_BATCH_DAY");
      loaded.triggerRequest().setTriggerType(TriggerType.CATCH_UP.code());
      jobMappers.triggerRequestMapper.updateTriggerType(
          request.tenantId(), request.requestId(), TriggerType.CATCH_UP.code());
    }
    return new LaunchRequest(
        request.tenantId(),
        request.jobCode(),
        request.bizDate(),
        lateAccepted ? TriggerType.EVENT : TriggerType.CATCH_UP,
        request.requestId(),
        request.traceId(),
        routedParams);
  }

  record BatchDayAuditLogParam(
      String tenantId,
      String traceId,
      String fromDayStatus,
      String toDayStatus,
      String calendarCode,
      LocalDate bizDate,
      String reasonCode,
      String operatorId,
      String operatorType,
      Integer lateCount,
      Integer catchupCount,
      Instant cutoffAt) {}

  private void appendBatchDayAuditLog(BatchDayAuditLogParam p) {
    JobExecutionLogEntity logEntity = new JobExecutionLogEntity();
    logEntity.setTenantId(p.tenantId());
    logEntity.setJobInstanceId(null);
    logEntity.setJobPartitionId(null);
    logEntity.setLogLevel("INFO");
    logEntity.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
    logEntity.setTraceId(p.traceId());
    logEntity.setMessage("BATCH_DAY_INSTANCE_STATE_CHANGED");
    logEntity.setDetailRef(AuditLogConstants.DETAIL_REF_BATCH_DAY_INSTANCE);
    logEntity.setExtraJson(
        JsonUtils.toJson(
            new LinkedHashMap<>() {
              {
                put("calendarCode", p.calendarCode());
                put("bizDate", p.bizDate() == null ? null : p.bizDate().toString());
                put("fromDayStatus", p.fromDayStatus());
                put("toDayStatus", p.toDayStatus());
                put("reasonCode", p.reasonCode());
                put("operatorId", p.operatorId());
                put("operatorType", p.operatorType());
                put("lateCount", p.lateCount());
                put("catchupCount", p.catchupCount());
                put("cutoffAt", p.cutoffAt() == null ? null : p.cutoffAt().toString());
              }
            }));
    jobExecutionLogMapper.insert(logEntity);
  }
}
