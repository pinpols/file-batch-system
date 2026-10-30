package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayOperationAuditEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceTransitionStatusByGenerationParam;
import io.github.pinpols.batch.orchestrator.domain.query.ExpectedWorkOccurrenceOverride;
import io.github.pinpols.batch.orchestrator.domain.value.BatchDayManualOperation;
import io.github.pinpols.batch.orchestrator.domain.view.BatchDayInstanceMetrics;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayOperationAuditMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
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
  private static final List<String> UNLAUNCHED_TRIGGER_REQUEST_STATUSES = List.of(
      TriggerRequestStatus.PENDING.code(),
      TriggerRequestStatus.PROCESSING.code(),
      TriggerRequestStatus.ACCEPTED.code(),
      TriggerRequestStatus.WAITING.code());

  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final BatchDayWaitingLaunchMapper waitingLaunchMapper;
  private final ExpectedWorkOccurrenceMapper expectedWorkOccurrenceMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final BatchDayOperationAuditMapper batchDayOperationAuditMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobDependencyWaitingLaunchMapper jobDependencyWaitingLaunchMapper;
  private final TriggerRequestMapper triggerRequestMapper;
  private final LaunchService launchService;
  private final BatchDateTimeSupport dateTimeSupport;

  /**
   * SETTLE_EMPTY 是人工确认动作而非自动推断：只允许 CUTOFF 且汇总实例数为零的业务日，原因进入 comment，
   * 固定 reason_code 表示操作类型。待处理的日历依赖和作业依赖启动请求必须先收敛，避免之后重检又在空日启动作业。
   */
  @Transactional
  public BatchDayOperationResult operate(BatchDayOperateCommand command) {
    boolean operationCommandIsInvalid = EmptyChecks.isNull(command)
        || !Texts.hasText(command.tenantId())
        || !Texts.hasText(command.calendarCode())
        || command.bizDate() == null
        || EmptyChecks.isNull(command.action());
    if (operationCommandIsInvalid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day.operation.invalid");
    }
    expectedWorkOccurrenceMapper.setTenantContext(command.tenantId());
    if (command.action() != BatchDayOperation.FREEZE) {
      batchDayInstanceMapper.acquireSettlementFence(
          command.tenantId(), command.calendarCode(), command.bizDate());
    }
    BatchDayInstanceEntity current = batchDayInstanceMapper.selectByTenantCalendarBizDate(
        command.tenantId(), command.calendarCode(), command.bizDate());
    if (current == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day.not_found");
    }
    requireReasonForManualDisposition(command);
    if (isManualTerminalDisposition(command.action())) {
      requireNoUnlaunchedTriggerRequests(current, command.action());
    }
    if (command.action() == BatchDayOperation.SETTLE_EMPTY) {
      requireEmptyDayCanSettle(current, command.reason());
    }
    List<ExpectedWorkOccurrenceOverride> waivedExpectedWork =
        command.action() == BatchDayOperation.SETTLE_EMPTY
            ? cancelBlockingExpectedWork(current)
            : List.of();
    int reopenedExpectedWork = command.action() == BatchDayOperation.REOPEN
        ? expectedWorkOccurrenceMapper.insertNextGenerationForCancelledByCalendarBizDate(
            current.tenantId(), current.calendarCode(), current.bizDate())
        : 0;
    List<ExpectedWorkOccurrenceOverride> openExpectedWorkAfterReopen =
        command.action() == BatchDayOperation.REOPEN
            ? expectedWorkOccurrenceMapper.selectBlockingByCalendarBizDateForUpdate(
                current.tenantId(), current.calendarCode(), current.bizDate())
            : List.of();
    Instant now = dateTimeSupport.nowInstant();
    String operator = Texts.hasText(command.operatorId()) ? command.operatorId() : "UNKNOWN";
    BatchDayInstanceEntity target =
        transition(current, command.action(), operator, command.reason(), now);
    int rows = batchDayInstanceMapper.updateWithCas(target);
    if (rows == 0) {
      throw new OptimisticLockingFailureException("batch_day_instance version mismatch: id="
          + current.id()
          + ", version="
          + current.version());
    }
    appendAuditLog(current, target, command.action(), operator, command.reason(), now);
    ExpectedWorkAuditChanges expectedWorkChanges = new ExpectedWorkAuditChanges(
        waivedExpectedWork, reopenedExpectedWork, openExpectedWorkAfterReopen);
    insertOperationAudit(command, current, target, operator, now, expectedWorkChanges);
    int released = command.action() == BatchDayOperation.RELEASE
        ? releaseWaitingLaunchesForBatchDay(target, operator)
        : 0;
    return new BatchDayOperationResult(target, released);
  }

  private List<ExpectedWorkOccurrenceOverride> cancelBlockingExpectedWork(
      BatchDayInstanceEntity batchDay) {
    List<ExpectedWorkOccurrenceOverride> occurrences =
        expectedWorkOccurrenceMapper.selectBlockingByCalendarBizDateForUpdate(
            batchDay.tenantId(), batchDay.calendarCode(), batchDay.bizDate());
    if (EmptyChecks.isEmpty(occurrences)) {
      return List.of();
    }
    for (ExpectedWorkOccurrenceOverride occurrence : occurrences) {
      ExpectedWorkOccurrenceTransitionStatusByGenerationParam
          expectedWorkOccurrenceTransitionStatusByGenerationParam =
              ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
                  .tenantId(batchDay.tenantId())
                  .calendarCode(batchDay.calendarCode())
                  .workflowDefinitionId(occurrence.workflowDefinitionId())
                  .sourceType(occurrence.sourceType())
                  .sourceKey(occurrence.sourceKey())
                  .bizDate(occurrence.bizDate())
                  .generation(occurrence.generation())
                  .expectedStatus(occurrence.occurrenceStatus())
                  .newStatus("CANCELLED")
                  .triggerRequestId(null)
                  .build();
      int updated = expectedWorkOccurrenceMapper.transitionStatus(
          expectedWorkOccurrenceTransitionStatusByGenerationParam);
      if (updated != 1) {
        throw new OptimisticLockingFailureException(
            "expected-work occurrence changed while settling empty batch day: id="
                + occurrence.id());
      }
    }
    return occurrences;
  }

  private void requireEmptyDayCanSettle(BatchDayInstanceEntity current, String reason) {
    if (!"CUTOFF".equals(normalize(current.dayStatus()))) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.batch_day.empty_settle_state_invalid",
          current.dayStatus());
    }
    if (!Texts.hasText(reason)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.batch_day.empty_settle_reason_required");
    }
    BatchDayInstanceMetrics metrics = jobInstanceMapper.selectBatchDayMetrics(
        current.tenantId(), current.calendarCode(), current.bizDate());
    boolean metricsMissing = EmptyChecks.isNull(metrics);
    boolean hasJobs = !metricsMissing && value(metrics.getTotalCount()) != 0L;
    if (metricsMissing || hasJobs) {
      Object reportedTotalCount = metricsMissing ? "unknown" : value(metrics.getTotalCount());
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.batch_day.empty_settle_has_jobs", reportedTotalCount);
    }
    List<BatchDayWaitingLaunchEntity> waitingLaunches =
        waitingLaunchMapper.selectWaitingByCalendarBizDate(
            current.tenantId(), current.calendarCode(), current.bizDate(), 1);
    boolean hasCalendarWait = EmptyChecks.isNotEmpty(waitingLaunches);
    int dependencyWaitCount = jobDependencyWaitingLaunchMapper.countWaitingForCalendarBizDate(
        current.tenantId(), current.calendarCode(), current.bizDate());
    int unlaunchedRequestCount = triggerRequestMapper.countUnlaunchedForCalendarBizDate(
        current.tenantId(),
        current.calendarCode(),
        current.bizDate(),
        UNLAUNCHED_TRIGGER_REQUEST_STATUSES);
    int scheduledOccurrenceCount = batchDayInstanceMapper.countBlockingScheduledOccurrences(
        current.tenantId(), current.calendarCode(), current.bizDate());
    int uncoveredScheduleCount = batchDayInstanceMapper.countUncoveredScheduledDefinitions(
        current.tenantId(), current.calendarCode(), current.cutoffAt());
    boolean settlementIsBlockedByOutstandingWork = hasCalendarWait
        || dependencyWaitCount > 0
        || unlaunchedRequestCount > 0
        || scheduledOccurrenceCount > 0
        || uncoveredScheduleCount > 0;
    if (settlementIsBlockedByOutstandingWork) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.batch_day.empty_settle_has_waits",
          hasCalendarWait,
          dependencyWaitCount,
          unlaunchedRequestCount,
          scheduledOccurrenceCount,
          uncoveredScheduleCount);
    }
  }

  private void requireReasonForManualDisposition(BatchDayOperateCommand command) {
    if (Texts.hasText(command.reason())) {
      return;
    }
    if (command.action() == BatchDayOperation.SKIP) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day.skip_reason_required");
    }
    if (command.action() == BatchDayOperation.REOPEN) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day.reopen_reason_required");
    }
  }

  private void requireNoUnlaunchedTriggerRequests(
      BatchDayInstanceEntity current, BatchDayOperation action) {
    int unlaunchedRequestCount = triggerRequestMapper.countUnlaunchedForCalendarBizDate(
        current.tenantId(),
        current.calendarCode(),
        current.bizDate(),
        UNLAUNCHED_TRIGGER_REQUEST_STATUSES);
    if (unlaunchedRequestCount > 0) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.batch_day.terminal_has_unlaunched_requests",
          action.name(),
          unlaunchedRequestCount);
    }
  }

  private boolean isManualTerminalDisposition(BatchDayOperation action) {
    return action == BatchDayOperation.SKIP
        || action == BatchDayOperation.CLOSE
        || action == BatchDayOperation.RELEASE;
  }

  private long value(Long value) {
    return EmptyChecks.isNull(value) ? 0L : value;
  }

  private record ExpectedWorkAuditChanges(
      List<ExpectedWorkOccurrenceOverride> waivedExpectedWork,
      int reopenedExpectedWork,
      List<ExpectedWorkOccurrenceOverride> openExpectedWorkAfterReopen) {}

  private void insertOperationAudit(
      BatchDayOperateCommand command,
      BatchDayInstanceEntity from,
      BatchDayInstanceEntity to,
      String operator,
      Instant now,
      ExpectedWorkAuditChanges expectedWorkChanges) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", command.tenantId());
    payload.put("calendarCode", command.calendarCode());
    payload.put("bizDate", command.bizDate() == null ? null : command.bizDate().toString());
    payload.put("action", command.action().name());
    payload.put("reason", command.reason());
    payload.put("operatorId", operator);
    if (EmptyChecks.isNotEmpty(expectedWorkChanges.waivedExpectedWork())) {
      payload.put(
          "waivedExpectedWork",
          expectedWorkChanges.waivedExpectedWork().stream()
              .map(this::toExpectedWorkAudit)
              .toList());
    }
    if (command.action() == BatchDayOperation.REOPEN) {
      payload.put("reopenedExpectedWorkCount", expectedWorkChanges.reopenedExpectedWork());
      payload.put("preservedTerminalOccurrences", true);
      payload.put(
          "openExpectedWorkAfterReopen",
          expectedWorkChanges.openExpectedWorkAfterReopen().stream()
              .map(this::toExpectedWorkAudit)
              .toList());
    }
    String auditReasonCode = command.action() == BatchDayOperation.SETTLE_EMPTY
        ? "EMPTY_DAY_CONFIRMED"
        : command.reason();
    String auditComment =
        command.action() == BatchDayOperation.SETTLE_EMPTY ? command.reason() : null;
    BatchDayOperationAuditEntity audit = BatchDayOperationAuditEntity.builder()
        .tenantId(from.tenantId())
        .calendarCode(from.calendarCode())
        .bizDate(from.bizDate())
        .operationType("BATCH_DAY_" + command.action().name())
        .fromStatus(from.dayStatus())
        .toStatus(to.dayStatus())
        .fromFrozen(from.frozen())
        .toFrozen(to.frozen())
        .operatorId(operator)
        .operatorType(AuditLogConstants.OPERATOR_TYPE_REQUEST)
        .reasonCode(auditReasonCode)
        .comment(auditComment)
        .approvalId(null)
        .requestPayload(JsonUtils.toJson(payload))
        .traceId(null)
        .createdAt(now)
        .build();
    batchDayOperationAuditMapper.insert(audit);
  }

  private Map<String, Object> toExpectedWorkAudit(ExpectedWorkOccurrenceOverride occurrence) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("id", occurrence.id());
    detail.put("generation", occurrence.generation());
    detail.put("workflowDefinitionId", occurrence.workflowDefinitionId());
    detail.put("sourceType", occurrence.sourceType());
    detail.put("sourceKey", occurrence.sourceKey());
    detail.put(
        "bizDate",
        EmptyChecks.isNull(occurrence.bizDate()) ? null : occurrence.bizDate().toString());
    detail.put("previousStatus", occurrence.occurrenceStatus());
    detail.put(
        "expectedAt",
        EmptyChecks.isNull(occurrence.expectedAt())
            ? null
            : occurrence.expectedAt().toString());
    detail.put(
        "dueAt",
        EmptyChecks.isNull(occurrence.dueAt()) ? null : occurrence.dueAt().toString());
    detail.put("triggerRequestId", occurrence.triggerRequestId());
    return detail;
  }

  private BatchDayInstanceEntity transition(
      BatchDayInstanceEntity current,
      BatchDayOperation action,
      String operator,
      String reason,
      Instant now) {
    String status = normalize(current.dayStatus());
    BatchDayManualOperation.BatchDayManualOperationBuilder base = BatchDayManualOperation.builder()
        .operationReason(reason)
        .operatedBy(operator)
        .operatedAt(now)
        .updatedAt(now);
    BatchDayManualOperation op =
        switch (action) {
          case FREEZE -> {
            requireNotTerminal(status, action);
            yield base.dayStatus(status)
                .frozen(true)
                .settledAt(current.settledAt())
                .build();
          }
          case RELEASE ->
            base.dayStatus("MANUAL_RELEASED").frozen(false).settledAt(now).build();
          case SKIP -> {
            requireNotTerminal(status, action);
            yield base.dayStatus("SKIPPED").frozen(false).settledAt(now).build();
          }
          case REOPEN -> {
            if (!TERMINAL_STATUSES.contains(status)) {
              throw BizException.of(
                  ResultCode.STATE_CONFLICT, "error.batch_day.reopen_state_invalid");
            }
            yield base.dayStatus("IN_FLIGHT").frozen(false).settledAt(null).build();
          }
          case CLOSE -> {
            if ("SETTLED".equals(status)) {
              throw BizException.of(
                  ResultCode.STATE_CONFLICT, "error.batch_day.close_state_invalid");
            }
            yield base.dayStatus("SETTLED").frozen(false).settledAt(now).build();
          }
          case SETTLE_EMPTY ->
            base.dayStatus("SETTLED").frozen(false).settledAt(now).build();
        };
    return current.withManualOperation(op);
  }

  private void requireNotTerminal(String status, BatchDayOperation action) {
    if (TERMINAL_STATUSES.contains(status)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.batch_day." + action.name().toLowerCase(Locale.ROOT));
    }
  }

  /**
   * 为已放行的批量日(`day_status` ∈ {SETTLED / SKIPPED / MANUAL_RELEASED})释放后一日 WAITING 启动请求。
   *
   * <p>逐条调用 {@link LaunchService#launch} 重新走 gate;成功后 {@code markReleased}。 调用方需自行确保入参 {@code
   * releasedDay} 处于可放行终态 — 本方法不再校验,只做批量释放动作。 自动调度入口 {@link
   * io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDayWaitingReleaseScheduler}
   * 与人工 {@link BatchDayOperation#RELEASE} 共用本方法,确保两条路径行为一致。
   */
  public int releaseWaitingLaunchesForBatchDay(
      BatchDayInstanceEntity releasedDay, String operator) {
    LocalDate waitingBizDate =
        releasedDay.bizDate() == null ? null : releasedDay.bizDate().plusDays(1);
    if (waitingBizDate == null) {
      return 0;
    }
    List<BatchDayWaitingLaunchEntity> waiting = waitingLaunchMapper.selectWaitingByCalendarBizDate(
        releasedDay.tenantId(), releasedDay.calendarCode(), waitingBizDate, RELEASE_WAITING_LIMIT);
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
    LaunchRequest.LaunchRequestBuilder requestBuilder = LaunchRequest.builder();
    String tenantId = stringValue(payload.get("tenantId"));
    requestBuilder.tenantId(tenantId);
    String jobCode = stringValue(payload.get("jobCode"));
    requestBuilder.jobCode(jobCode);
    String businessDateValue = stringValue(payload.get("bizDate"));
    LocalDate businessDate = LocalDate.parse(businessDateValue);
    requestBuilder.bizDate(businessDate);
    requestBuilder.triggerType(TriggerType.valueOf(triggerType));
    String requestId = stringValue(payload.get("requestId"));
    requestBuilder.requestId(requestId);
    String traceId = stringValue(payload.get("traceId"));
    requestBuilder.traceId(traceId);
    Map<String, Object> params = (Map<String, Object>) payload.getOrDefault("params", Map.of());
    requestBuilder.params(params);
    return requestBuilder.build();
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
    String extraJson = JsonUtils.toJson(extra);
    audit.setExtraJson(extraJson);
    jobExecutionLogMapper.insert(audit);
  }

  private String normalize(String value) {
    if (!Texts.hasText(value)) {
      return "";
    }
    String trimmedValue = value.trim();
    return trimmedValue.toUpperCase(Locale.ROOT);
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  public enum BatchDayOperation {
    FREEZE,
    RELEASE,
    SKIP,
    REOPEN,
    CLOSE,
    SETTLE_EMPTY
  }

  public record BatchDayOperationResult(BatchDayInstanceEntity batchDay, int releasedLaunchCount) {}
}
