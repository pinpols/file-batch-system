package com.example.batch.orchestrator.service;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.service.OrchestratorJobMappers;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.service.LaunchValidationService.LaunchLoadResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 批次日（Batch Day）生命周期管理：upsert、cutoff 判定、late arrival 路由、审计日志。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LaunchBatchDayService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String REASON_LATE_ACCEPTED = "LATE_ACCEPTED";
  private static final int UPSERT_MAX_ATTEMPTS = 3;

  private final OrchestratorConfigCacheService configCacheService;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final OrchestratorJobMappers jobMappers;
  private final BatchTimezoneProvider timezoneProvider;
  private final ObjectProvider<LaunchBatchDayService> selfProvider;

  /**
   * 批次日 upsert 入口：内部以 REQUIRES_NEW 事务逐次尝试；遇到 @Version 乐观锁冲突时 （{@link
   * OptimisticLockingFailureException}）重新读 existing 再试，最多 {@link #UPSERT_MAX_ATTEMPTS} 次， 避免把并发路径的
   * CAS 冲突反向污染外层 launch 事务（T1）。
   *
   * <p>独立事务意味着 batch_day 行可能先于外层 T1 提交；外层 T1 若因 job_instance 唯一键回滚， late_count / catchup_count 的+1
   * 会留下（记录的是"尝试"而非"成功"），权衡后可接受。
   */
  void upsertBatchDayInstance(
      LaunchRequest request,
      JobDefinitionEntity jobDefinition,
      Map<String, Object> effectiveParams,
      Instant batchDaySlaDeadlineAt) {
    DataAccessException last = null;
    for (int attempt = 1; attempt <= UPSERT_MAX_ATTEMPTS; attempt++) {
      try {
        selfProvider
            .getObject()
            .doUpsertBatchDayInstance(
                request, jobDefinition, effectiveParams, batchDaySlaDeadlineAt);
        return;
      } catch (OptimisticLockingFailureException conflict) {
        last = conflict;
        log.info(
            "batch day upsert cas conflict; will retry: tenantId={}, jobCode={}, bizDate={},"
                + " attempt={}/{}",
            request == null ? null : request.tenantId(),
            request == null ? null : request.jobCode(),
            request == null ? null : request.bizDate(),
            attempt,
            UPSERT_MAX_ATTEMPTS);
      } catch (DuplicateKeyException concurrentInsert) {
        last = concurrentInsert;
        log.info(
            "batch day upsert concurrent insert (uk_batch_day_instance); will retry as update:"
                + " tenantId={}, jobCode={}, bizDate={}, attempt={}/{}",
            request == null ? null : request.tenantId(),
            request == null ? null : request.jobCode(),
            request == null ? null : request.bizDate(),
            attempt,
            UPSERT_MAX_ATTEMPTS);
      }
    }
    throw last;
  }

  /**
   * 单次 upsert 尝试：独立 REQUIRES_NEW 事务，保证 CAS 冲突时只回滚本次尝试的写入， 不会污染外层 launch 事务。必须是 {@code public} 并通过
   * self-proxy 调用以走 Spring AOP 织入。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void doUpsertBatchDayInstance(
      LaunchRequest request,
      JobDefinitionEntity jobDefinition,
      Map<String, Object> effectiveParams,
      Instant batchDaySlaDeadlineAt) {
    if (isMissingLaunchContext(request, jobDefinition)) {
      return;
    }
    String calendarCode = LaunchParamResolver.textValue(jobDefinition.calendarCode());
    if (!Texts.hasText(calendarCode)) {
      return;
    }
    Instant now = Instant.now();
    BatchDayInstanceEntity existing =
        batchDayInstanceMapper.selectByTenantCalendarBizDate(
            request.tenantId(), calendarCode, request.bizDate());
    Instant cutoffAt = resolveBatchDayCutoffAt(request.tenantId(), calendarCode, request.bizDate());
    String timezoneSnapshot = resolveCalendarTimezone(request.tenantId(), calendarCode);
    String operatorId = LaunchParamResolver.resolveOperatorId(effectiveParams);
    String auditOperatorId =
        Texts.hasText(operatorId) ? operatorId : AuditLogConstants.OPERATOR_ID_SYSTEM;
    String auditOperatorType =
        Texts.hasText(operatorId)
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
      batchDayInstanceMapper.insert(
          new BatchDayInstanceEntity(
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
              timezoneSnapshot,
              // version=0 让 mapper.xml 默认值生效（非 null 才走显式赋值）；version=null 也可，xml 兜 0
              0L,
              now,
              now));
      BatchDayAuditLogParam auditParam =
          BatchDayAuditLogParam.builder()
              .tenantId(request.tenantId())
              .traceId(request.traceId())
              .toDayStatus(dayStatus)
              .calendarCode(calendarCode)
              .bizDate(request.bizDate())
              .reasonCode(reasonCode)
              .operatorId(auditOperatorId)
              .operatorType(auditOperatorType)
              .lateCount(lateAccepted ? 1 : 0)
              .catchupCount(catchUpLaunch ? 1 : 0)
              .cutoffAt(cutoffAt)
              .build();
      appendBatchDayAuditLog(auditParam);
      return;
    }
    boolean lateAccepted = isLateAccepted(effectiveParams);
    boolean catchUpLaunch = isCatchUpLaunch(request);
    boolean pastCutoff = cutoffAt != null && !now.isBefore(cutoffAt);
    boolean shouldMoveToCutoff = "OPEN".equalsIgnoreCase(existing.dayStatus()) && pastCutoff;
    boolean shouldReopen =
        shouldReopenBatchDay(existing.dayStatus()) || lateAccepted || catchUpLaunch;
    BatchDayInstanceEntity updated = existing;
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
    int rows = batchDayInstanceMapper.updateWithCas(updated);
    if (rows == 0) {
      // CAS 冲突：版本与 DB 不一致；抛出由外层 upsertBatchDayInstance 的重试循环捕获
      throw new OptimisticLockingFailureException(
          "batch_day_instance version mismatch: id="
              + updated.id()
              + ", version="
              + updated.version());
    }
    BatchDayAuditLogParam auditParam =
        BatchDayAuditLogParam.builder()
            .tenantId(request.tenantId())
            .traceId(request.traceId())
            .fromDayStatus(fromDayStatus)
            .toDayStatus(toDayStatus)
            .calendarCode(calendarCode)
            .bizDate(request.bizDate())
            .reasonCode(reasonCode == null ? "BATCH_DAY_UPDATED" : reasonCode)
            .operatorId(auditOperatorId)
            .operatorType(auditOperatorType)
            .lateCount(updated.lateCount())
            .catchupCount(updated.catchupCount())
            .cutoffAt(cutoffAt)
            .build();
    appendBatchDayAuditLog(auditParam);
  }

  Instant resolveBatchDayCutoffAt(String tenantId, String calendarCode, LocalDate bizDate) {
    BusinessCalendarEntity calendar =
        configCacheService.findEnabledBusinessCalendar(tenantId, calendarCode);
    if (calendar == null) {
      return null;
    }
    LocalTime cutoffTime =
        calendar.cutoffTime() == null ? LocalTime.of(6, 0) : calendar.cutoffTime();
    ZoneId zoneId = timezoneProvider.resolveOrDefault(calendar.timezone());
    return bizDate.plusDays(1).atTime(cutoffTime).atZone(zoneId).toInstant();
  }

  /**
   * 创建批次日实例时快照日历 timezone；日历未绑 timezone 时回退到平台默认 （{@code batch.timezone.default-zone}，默认 {@code
   * Asia/Shanghai}），避免落 'UTC' 导致事后 cutoff 回放偏差。
   */
  String resolveCalendarTimezone(String tenantId, String calendarCode) {
    BusinessCalendarEntity calendar =
        configCacheService.findEnabledBusinessCalendar(tenantId, calendarCode);
    if (calendar != null && Texts.hasText(calendar.timezone())) {
      return calendar.timezone();
    }
    return timezoneProvider.defaultZone().getId();
  }

  Instant resolveBatchDaySlaDeadlineAt(String tenantId, String calendarCode, LocalDate bizDate) {
    BusinessCalendarEntity calendar =
        configCacheService.findEnabledBusinessCalendar(tenantId, calendarCode);
    if (!hasValidSlaOffset(calendar)) {
      return null;
    }
    LocalTime cutoffTime =
        calendar.cutoffTime() == null ? LocalTime.of(6, 0) : calendar.cutoffTime();
    ZoneId zoneId = timezoneProvider.resolveOrDefault(calendar.timezone());
    Instant cutoffAt = bizDate.plusDays(1).atTime(cutoffTime).atZone(zoneId).toInstant();
    return cutoffAt.plusSeconds(calendar.slaOffsetMin() * 60L);
  }

  boolean shouldReopenBatchDay(String dayStatus) {
    return "FAILED".equalsIgnoreCase(dayStatus) || "SETTLED".equalsIgnoreCase(dayStatus);
  }

  boolean isPastBatchDayCutoff(BatchDayInstanceEntity batchDay, String calendarCode) {
    if (isIncompleteBatchDay(batchDay)) {
      return false;
    }
    Instant cutoffAt = batchDay.cutoffAt();
    if (cutoffAt == null) {
      cutoffAt = resolveBatchDayCutoffAt(batchDay.tenantId(), calendarCode, batchDay.bizDate());
    }
    return cutoffAt != null && !Instant.now().isBefore(cutoffAt);
  }

  boolean isWithinLateArrivalTolerance(BatchDayInstanceEntity batchDay, String calendarCode) {
    if (batchDay == null || !Texts.hasText(calendarCode)) {
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
    BusinessCalendarEntity calendar =
        configCacheService.findEnabledBusinessCalendar(tenantId, calendarCode);
    if (!hasValidLateArrivalTolerance(calendar)) {
      return 0;
    }
    return calendar.lateArrivalToleranceMin();
  }

  private boolean isMissingLaunchContext(LaunchRequest request, JobDefinitionEntity jobDefinition) {
    return request == null || request.bizDate() == null || jobDefinition == null;
  }

  private boolean isIncompleteBatchDay(BatchDayInstanceEntity batchDay) {
    return batchDay == null || batchDay.bizDate() == null || batchDay.tenantId() == null;
  }

  private boolean hasValidSlaOffset(BusinessCalendarEntity calendar) {
    return calendar != null && calendar.slaOffsetMin() != null && calendar.slaOffsetMin() > 0;
  }

  private boolean hasValidLateArrivalTolerance(BusinessCalendarEntity calendar) {
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
    if (!Texts.hasText(calendarCode)) {
      return request;
    }
    BatchDayInstanceEntity batchDay =
        batchDayInstanceMapper.selectByTenantCalendarBizDate(
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
      return new LaunchRequest(
          request.tenantId(),
          request.jobCode(),
          request.bizDate(),
          TriggerType.EVENT,
          request.requestId(),
          request.traceId(),
          routedParams);
    }
    // late-rejected：先 DB CAS（仅当 trigger_type 仍为 EVENT 时才能翻为 CATCH_UP），
    // 成功后再同步内存 LaunchRequest / triggerRequest；CAS 未命中说明并发路径已改过状态，
    // 此时必须以 DB 的最新 trigger_type 为准同步内存，避免内存持续持有过期 EVENT 误导 prepareJobInstance。
    routedParams.put("catchUpReason", "LATE_ARRIVAL_OR_CLOSED_BATCH_DAY");
    int casRows =
        jobMappers.triggerRequestMapper.updateTriggerType(
            request.tenantId(),
            request.requestId(),
            TriggerType.CATCH_UP.code(),
            TriggerType.EVENT.code());
    if (casRows == 0) {
      TriggerRequestEntity latest =
          jobMappers.triggerRequestMapper.selectByTenantAndRequestId(
              request.tenantId(), request.requestId());
      if (latest == null) {
        return request;
      }
      loaded.triggerRequest().setTriggerType(latest.getTriggerType());
      if (!TriggerType.CATCH_UP.code().equals(latest.getTriggerType())) {
        return request;
      }
      return new LaunchRequest(
          request.tenantId(),
          request.jobCode(),
          request.bizDate(),
          TriggerType.CATCH_UP,
          request.requestId(),
          request.traceId(),
          routedParams);
    }
    loaded.triggerRequest().setTriggerType(TriggerType.CATCH_UP.code());
    return new LaunchRequest(
        request.tenantId(),
        request.jobCode(),
        request.bizDate(),
        TriggerType.CATCH_UP,
        request.requestId(),
        request.traceId(),
        routedParams);
  }

  @Builder
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
