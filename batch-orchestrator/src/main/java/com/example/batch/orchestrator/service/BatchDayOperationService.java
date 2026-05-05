package com.example.batch.orchestrator.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BatchDayOperationService {

  private static final int RELEASE_WAITING_LIMIT = 200;
  private static final List<String> TERMINAL_STATUSES =
      List.of("SETTLED", "FAILED", "SKIPPED", "MANUAL_RELEASED");

  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final BatchDayWaitingLaunchMapper waitingLaunchMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final LaunchService launchService;
  private final BatchDateTimeSupport dateTimeSupport;

  @Transactional
  public BatchDayOperationResult operate(
      String tenantId,
      String calendarCode,
      LocalDate bizDate,
      BatchDayOperation action,
      String operatorId,
      String reason) {
    if (!Texts.hasText(tenantId)
        || !Texts.hasText(calendarCode)
        || bizDate == null
        || action == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day.operation.invalid");
    }
    BatchDayInstanceEntity current =
        batchDayInstanceMapper.selectByTenantCalendarBizDate(tenantId, calendarCode, bizDate);
    if (current == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day.not_found");
    }
    Instant now = dateTimeSupport.nowInstant();
    String operator = Texts.hasText(operatorId) ? operatorId : "UNKNOWN";
    BatchDayInstanceEntity target = transition(current, action, operator, reason, now);
    int rows = batchDayInstanceMapper.updateWithCas(target);
    if (rows == 0) {
      throw new OptimisticLockingFailureException(
          "batch_day_instance version mismatch: id="
              + current.id()
              + ", version="
              + current.version());
    }
    appendAuditLog(current, target, action, operator, reason, now);
    int released =
        action == BatchDayOperation.RELEASE ? releaseWaitingLaunches(target, operator) : 0;
    return new BatchDayOperationResult(target, released);
  }

  private BatchDayInstanceEntity transition(
      BatchDayInstanceEntity current,
      BatchDayOperation action,
      String operator,
      String reason,
      Instant now) {
    String status = normalize(current.dayStatus());
    return switch (action) {
      case FREEZE -> {
        requireNotTerminal(status, action);
        yield current.withManualOperation(
            status, true, reason, operator, now, current.settledAt(), now);
      }
      case RELEASE ->
          current.withManualOperation("MANUAL_RELEASED", false, reason, operator, now, now, now);
      case SKIP -> {
        requireNotTerminal(status, action);
        yield current.withManualOperation("SKIPPED", false, reason, operator, now, now, now);
      }
      case REOPEN -> {
        if (!TERMINAL_STATUSES.contains(status)) {
          throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day.reopen_state_invalid");
        }
        yield current.withManualOperation("IN_FLIGHT", false, reason, operator, now, null, now);
      }
      case CLOSE -> {
        if ("SETTLED".equals(status)) {
          throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day.close_state_invalid");
        }
        yield current.withManualOperation("SETTLED", false, reason, operator, now, now, now);
      }
    };
  }

  private void requireNotTerminal(String status, BatchDayOperation action) {
    if (TERMINAL_STATUSES.contains(status)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.batch_day." + action.name().toLowerCase(Locale.ROOT));
    }
  }

  private int releaseWaitingLaunches(BatchDayInstanceEntity releasedDay, String operator) {
    LocalDate waitingBizDate =
        releasedDay.bizDate() == null ? null : releasedDay.bizDate().plusDays(1);
    if (waitingBizDate == null) {
      return 0;
    }
    List<BatchDayWaitingLaunchEntity> waiting =
        waitingLaunchMapper.selectWaitingByCalendarBizDate(
            releasedDay.tenantId(),
            releasedDay.calendarCode(),
            waitingBizDate,
            RELEASE_WAITING_LIMIT);
    if (waiting == null || waiting.isEmpty()) {
      return 0;
    }
    int released = 0;
    for (BatchDayWaitingLaunchEntity entity : waiting) {
      LaunchRequest request = toLaunchRequest(entity);
      if (request == null) {
        continue;
      }
      launchService.launch(request);
      waitingLaunchMapper.markReleased(entity.tenantId(), entity.requestId(), operator);
      released++;
    }
    return released;
  }

  @SuppressWarnings("unchecked")
  private LaunchRequest toLaunchRequest(BatchDayWaitingLaunchEntity entity) {
    if (entity == null || !Texts.hasText(entity.launchPayload())) {
      return null;
    }
    Map<String, Object> payload = JsonUtils.fromJson(entity.launchPayload(), Map.class);
    String triggerType = stringValue(payload.get("triggerType"));
    return new LaunchRequest(
        stringValue(payload.get("tenantId")),
        stringValue(payload.get("jobCode")),
        LocalDate.parse(stringValue(payload.get("bizDate"))),
        TriggerType.valueOf(triggerType),
        stringValue(payload.get("requestId")),
        stringValue(payload.get("traceId")),
        (Map<String, Object>) payload.getOrDefault("params", Map.of()));
  }

  private void appendAuditLog(
      BatchDayInstanceEntity from,
      BatchDayInstanceEntity to,
      BatchDayOperation action,
      String operator,
      String reason,
      Instant now) {
    JobExecutionLogEntity audit = new JobExecutionLogEntity();
    audit.setTenantId(from.tenantId());
    audit.setLogLevel("INFO");
    audit.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
    audit.setMessage("BATCH_DAY_MANUAL_OPERATION");
    audit.setDetailRef(AuditLogConstants.DETAIL_REF_BATCH_DAY_INSTANCE);
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("calendarCode", from.calendarCode());
    extra.put("bizDate", from.bizDate() == null ? null : from.bizDate().toString());
    extra.put("operation", action.name());
    extra.put("fromDayStatus", from.dayStatus());
    extra.put("toDayStatus", to.dayStatus());
    extra.put("fromFrozen", from.frozen());
    extra.put("toFrozen", to.frozen());
    extra.put("reasonCode", reason);
    extra.put("operatorId", operator);
    extra.put("operatorType", AuditLogConstants.OPERATOR_TYPE_REQUEST);
    extra.put("at", now.toString());
    audit.setExtraJson(JsonUtils.toJson(extra));
    jobExecutionLogMapper.insert(audit);
  }

  private String normalize(String value) {
    return Texts.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  public enum BatchDayOperation {
    FREEZE,
    RELEASE,
    SKIP,
    REOPEN,
    CLOSE
  }

  public record BatchDayOperationResult(BatchDayInstanceEntity batchDay, int releasedLaunchCount) {}
}
