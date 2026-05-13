package com.example.batch.console.infrastructure.query;

import static com.example.batch.console.infrastructure.query.ConsoleQuerySupport.*;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.i18n.LocalizedErrorRenderer;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.console.domain.entity.ApprovalCommandEntity;
import com.example.batch.console.domain.entity.ConsoleAiAuditLogEntity;
import com.example.batch.console.domain.entity.DeadLetterTaskEntity;
import com.example.batch.console.domain.entity.PendingCatchUpEntity;
import com.example.batch.console.domain.entity.RetryScheduleEntity;
import com.example.batch.console.domain.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.query.AlertEventQuery;
import com.example.batch.console.domain.query.ApprovalCommandQuery;
import com.example.batch.console.domain.query.AuditLogQuery;
import com.example.batch.console.domain.query.ConsoleAiAuditLogQuery;
import com.example.batch.console.domain.query.DeadLetterTaskQuery;
import com.example.batch.console.domain.query.OutboxDeliveryLogQuery;
import com.example.batch.console.domain.query.OutboxRetryLogQuery;
import com.example.batch.console.domain.query.PendingCatchUpQuery;
import com.example.batch.console.domain.query.RetryScheduleQuery;
import com.example.batch.console.domain.query.WorkerRegistryQuery;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.querymap.ConsoleOpsQueryMappers;
import com.example.batch.console.web.query.AlertEventQueryRequest;
import com.example.batch.console.web.query.ApprovalCommandQueryRequest;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.BatchDayQueryRequest;
import com.example.batch.console.web.query.BatchDayWindowQueryRequest;
import com.example.batch.console.web.query.ConsoleAiAuditLogQueryRequest;
import com.example.batch.console.web.query.DeadLetterQueryRequest;
import com.example.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import com.example.batch.console.web.query.OutboxRetryLogQueryRequest;
import com.example.batch.console.web.query.PendingCatchUpQueryRequest;
import com.example.batch.console.web.query.RetryScheduleQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import com.example.batch.console.web.response.auth.AiAuditLogResponse;
import com.example.batch.console.web.response.file.ConsoleBatchDayResponse;
import com.example.batch.console.web.response.file.ConsoleBatchDaySummaryResponse;
import com.example.batch.console.web.response.file.ConsoleBatchDayWindowResponse;
import com.example.batch.console.web.response.job.ConsoleRetryScheduleResponse;
import com.example.batch.console.web.response.ops.ConsoleAlertEventResponse;
import com.example.batch.console.web.response.ops.ConsoleApprovalCommandResponse;
import com.example.batch.console.web.response.ops.ConsoleAuditLogResponse;
import com.example.batch.console.web.response.ops.ConsoleDeadLetterTaskResponse;
import com.example.batch.console.web.response.ops.ConsoleOutboxDeliveryLogResponse;
import com.example.batch.console.web.response.ops.ConsoleOutboxRetryLogResponse;
import com.example.batch.console.web.response.ops.ConsolePendingCatchUpResponse;
import com.example.batch.console.web.response.ops.ConsoleWorkerRegistryResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 运维相关查询子服务。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleOpsQueryService {

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleOpsQueryMappers opsMappers;
  private final LocalizedErrorRenderer localizedErrorRenderer;
  private final BatchTimezoneProvider timezoneProvider;

  public PageResponse<ConsoleAuditLogResponse> auditLogs(AuditLogQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    AuditLogQuery query = new AuditLogQuery();
    query.setTenantId(resolveTenant(tenantGuard, request.getTenantId()));
    query.setOperationType(request.getOperationType());
    query.setOperationResult(request.getOperationResult());
    query.setOperatorId(request.getOperatorId());
    query.setFileId(parseLong(request.getFileId(), "fileId"));
    query.setTraceId(request.getTraceId());
    query.setFromTime(
        parseFlexibleInstant(
            firstNonBlank(request.getFromTime(), request.getStartTime()),
            "fromTime",
            timezoneProvider.defaultZone()));
    query.setToTime(
        parseFlexibleInstant(
            firstNonBlank(request.getToTime(), request.getEndTime()),
            "toTime",
            timezoneProvider.defaultZone()));
    query.setPageRequest(pageRequest);
    List<Map<String, Object>> rows = opsMappers.auditLogMapper.selectByQuery(query);
    long total = opsMappers.auditLogMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toAuditLogResponse);
  }

  public PageResponse<ConsoleAuditLogResponse> executionLogs(AuditLogQueryRequest request) {
    return auditLogs(request);
  }

  public PageResponse<ConsoleOutboxRetryLogResponse> outboxRetries(
      OutboxRetryLogQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    List<Map<String, Object>> rows =
        opsMappers.outboxRetryLogMapper.selectByQuery(
            new OutboxRetryLogQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getRetryStatus(),
                request.getEventKey(),
                pageRequest));
    long total =
        opsMappers.outboxRetryLogMapper.countByQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getRetryStatus(),
            request.getEventKey(),
            pageRequest);
    return page(pageRequest, total, rows, this::toOutboxRetryResponse);
  }

  public PageResponse<ConsoleOutboxDeliveryLogResponse> outboxDeliveries(
      OutboxDeliveryLogQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    List<Map<String, Object>> rows =
        opsMappers.outboxDeliveryLogMapper.selectByQuery(
            new OutboxDeliveryLogQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getDeliveryStatus(),
                request.getEventType(),
                request.getEventKey(),
                pageRequest));
    long total =
        opsMappers.outboxDeliveryLogMapper.countByQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getDeliveryStatus(),
            request.getEventType(),
            request.getEventKey(),
            pageRequest);
    return page(pageRequest, total, rows, this::toOutboxDeliveryResponse);
  }

  public PageResponse<AiAuditLogResponse> aiAuditLogs(ConsoleAiAuditLogQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    List<ConsoleAiAuditLogEntity> rows =
        opsMappers.consoleAiAuditLogMapper.selectByQuery(
            new ConsoleAiAuditLogQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getSessionId(),
                request.getOperatorId(),
                request.getPromptCategory(),
                request.getPromptDecision(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime"),
                pageRequest));
    long total =
        opsMappers.consoleAiAuditLogMapper.countByQuery(
            new ConsoleAiAuditLogQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getSessionId(),
                request.getOperatorId(),
                request.getPromptCategory(),
                request.getPromptDecision(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime"),
                pageRequest));
    return page(
        pageRequest,
        total,
        rows,
        entity -> {
          AiAuditLogResponse row = new AiAuditLogResponse();
          row.setId(entity.getId());
          row.setTenantId(entity.getTenantId());
          row.setRequestId(entity.getRequestId());
          row.setTraceId(entity.getTraceId());
          row.setSessionId(entity.getSessionId());
          row.setOperatorId(entity.getOperatorId());
          row.setPromptCategory(entity.getPromptCategory());
          row.setPromptDecision(entity.getPromptDecision());
          row.setModelName(entity.getModelName());
          row.setPromptPreview(ConsoleTextSanitizer.safeDisplay(entity.getPromptPreview(), 512));
          row.setResponsePreview(
              ConsoleTextSanitizer.safeDisplay(entity.getResponsePreview(), 512));
          row.setRefusalReason(ConsoleTextSanitizer.safeDisplay(entity.getRefusalReason(), 512));
          row.setCreatedAt(entity.getCreatedAt());
          return row;
        });
  }

  public PageResponse<ConsoleDeadLetterTaskResponse> deadLetters(DeadLetterQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    DeadLetterTaskQuery query =
        new DeadLetterTaskQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getSourceType(),
            request.getReplayStatus(),
            request.getTraceId(),
            pageRequest);
    List<DeadLetterTaskEntity> rows = opsMappers.deadLetterTaskMapper.selectByQuery(query);
    long total = opsMappers.deadLetterTaskMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toDeadLetterTaskResponse);
  }

  public PageResponse<ConsoleRetryScheduleResponse> retries(RetryScheduleQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    RetryScheduleQuery query =
        new RetryScheduleQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getRelatedType(),
            request.getRetryPolicy(),
            request.getRetryStatus(),
            pageRequest);
    List<RetryScheduleEntity> rows = opsMappers.retryScheduleMapper.selectByQuery(query);
    long total = opsMappers.retryScheduleMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toRetryScheduleResponse);
  }

  public PageResponse<ConsolePendingCatchUpResponse> pendingCatchUps(
      PendingCatchUpQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    PendingCatchUpQuery query =
        new PendingCatchUpQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getJobCode(),
            request.getRequestId(),
            pageRequest);
    List<PendingCatchUpEntity> rows = opsMappers.pendingCatchUpMapper.selectByQuery(query);
    long total = opsMappers.pendingCatchUpMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toPendingCatchUpResponse);
  }

  public PageResponse<ConsoleWorkerRegistryResponse> workers(WorkerRegistryQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    WorkerRegistryQuery query =
        new WorkerRegistryQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getWorkerGroup(),
            request.getStatus(),
            pageRequest);
    List<WorkerRegistryEntity> rows = opsMappers.workerRegistryMapper.selectByQuery(query);
    long total = opsMappers.workerRegistryMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toWorkerRegistryResponse);
  }

  public PageResponse<ConsoleAlertEventResponse> alertEvents(AlertEventQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    AlertEventQuery query =
        new AlertEventQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getSeverity(),
            request.getStatus(),
            request.getAlertType(),
            pageRequest);
    List<AlertEventEntity> rows = opsMappers.alertEventMapper.selectByQuery(query);
    long total = opsMappers.alertEventMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toAlertEventResponse);
  }

  public PageResponse<ConsoleBatchDayResponse> batchDays(BatchDayQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    LocalDate fromBizDate = parseLocalDate(request.getFrom(), "from");
    LocalDate toBizDate = parseLocalDate(request.getTo(), "to");
    List<Map<String, Object>> rows =
        opsMappers.batchDayMapper.selectByQuery(
            tenantId, request.getCalendarCode(), fromBizDate, toBizDate, pageRequest);
    long total =
        opsMappers.batchDayMapper.countByQuery(
            tenantId, request.getCalendarCode(), fromBizDate, toBizDate);
    List<ConsoleBatchDayResponse> responses =
        rows.stream()
            .map(row -> toBatchDayResponse(tenantId, request.getCalendarCode(), row))
            .toList();
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), responses);
  }

  public ConsoleBatchDayWindowResponse batchDayWindow(
      String bizDate, BatchDayWindowQueryRequest request) {
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    LocalDate parsedBizDate = parseLocalDate(bizDate, "bizDate");
    return toBatchDayWindowResponse(
        tenantId,
        request.getCalendarCode(),
        parsedBizDate,
        opsMappers.batchDayMapper.selectWindow(tenantId, request.getCalendarCode(), parsedBizDate));
  }

  public PageResponse<ConsoleApprovalCommandResponse> approvals(
      ApprovalCommandQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    ApprovalCommandQuery query = new ApprovalCommandQuery();
    query.setTenantId(resolveTenant(tenantGuard, request.getTenantId()));
    query.setApprovalNo(request.getApprovalNo());
    query.setApprovalType(request.getApprovalType());
    query.setActionType(request.getActionType());
    query.setApprovalStatus(request.getApprovalStatus());
    query.setPageRequest(pageRequest);
    List<ApprovalCommandEntity> rows = opsMappers.approvalCommandMapper.selectByQuery(query);
    long total = opsMappers.approvalCommandMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toApprovalResponse);
  }

  private ConsoleAuditLogResponse toAuditLogResponse(Map<String, Object> row) {
    return new ConsoleAuditLogResponse(
        longValue(row, "id"),
        stringValue(row, "tenant_id"),
        longValue(row, "file_id"),
        stringValue(row, "operation_type"),
        stringValue(row, "operation_result"),
        stringValue(row, "operator_type"),
        stringValue(row, "operator_id"),
        stringValue(row, "trace_id"),
        stringValue(row, "evidence_ref"),
        stringValue(row, "detail_summary"),
        instantValue(row, "created_at"));
  }

  private ConsoleOutboxRetryLogResponse toOutboxRetryResponse(Map<String, Object> row) {
    return new ConsoleOutboxRetryLogResponse(
        longValue(row, "id"),
        stringValue(row, "tenant_id"),
        stringValue(row, "event_type"),
        stringValue(row, "event_key"),
        stringValue(row, "retry_status"),
        intValue(row, "retry_count"),
        stringValue(row, "retry_policy"),
        instantValue(row, "next_retry_at"),
        instantValue(row, "created_at"),
        instantValue(row, "updated_at"));
  }

  private ConsoleOutboxDeliveryLogResponse toOutboxDeliveryResponse(Map<String, Object> row) {
    String errorMessage =
        localizedErrorRenderer.render(
            stringValue(row, "error_key"),
            stringValue(row, "error_args"),
            stringValue(row, "error_message"));
    return new ConsoleOutboxDeliveryLogResponse(
        longValue(row, "id"),
        stringValue(row, "tenant_id"),
        stringValue(row, "event_type"),
        stringValue(row, "event_key"),
        stringValue(row, "delivery_status"),
        stringValue(row, "target_topic"),
        intValue(row, "delivery_attempt"),
        errorMessage,
        instantValue(row, "created_at"),
        instantValue(row, "updated_at"));
  }

  private ConsoleApprovalCommandResponse toApprovalResponse(ApprovalCommandEntity entity) {
    return new ConsoleApprovalCommandResponse(
        entity.getId(),
        display(entity.getTenantId()),
        display(entity.getApprovalNo()),
        display(entity.getApprovalType()),
        display(entity.getActionType()),
        display(entity.getTargetType()),
        display(entity.getTargetId()),
        entity.getPayloadJson(),
        display(entity.getApprovalStatus()),
        display(entity.getRequesterId()),
        display(entity.getApproverId()),
        display(entity.getRejectionReason()),
        display(entity.getApprovalReason()),
        display(entity.getSourceTraceId()),
        display(entity.getSourceIdempotencyKey()),
        entity.getApprovedAt(),
        entity.getExecutedAt(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private ConsoleBatchDayResponse toBatchDayResponse(
      String tenantId, String calendarCode, Map<String, Object> row) {
    LocalDate bizDate = localDateValue(row, "bizDate");
    List<ConsoleBatchDaySummaryResponse> summaries =
        loadBatchDaySummaries(tenantId, calendarCode, bizDate);
    List<ConsoleBatchDaySummaryResponse> catchupSummary =
        summaries.stream()
            .filter(
                summary ->
                    safeInt(summary.failedJobCount()) > 0 || safeInt(summary.catchupCount()) > 0)
            .toList();
    return new ConsoleBatchDayResponse(
        bizDate,
        stringValue(row, "dayStatus"),
        instantValue(row, "openAt"),
        instantValue(row, "cutoffAt"),
        instantValue(row, "settledAt"),
        instantValue(row, "slaDeadlineAt"),
        resolveSlaStatus(instantValue(row, "slaDeadlineAt"), instantValue(row, "settledAt")),
        intValue(row, "totalJobCount"),
        intValue(row, "successJobCount"),
        intValue(row, "failedJobCount"),
        intValue(row, "inFlightJobCount"),
        intValue(row, "lateCount"),
        intValue(row, "catchupCount"),
        catchupSummary);
  }

  private ConsoleBatchDayWindowResponse toBatchDayWindowResponse(
      String tenantId, String calendarCode, LocalDate bizDate, Map<String, Object> row) {
    Instant cutoffAt = instantValue(row, "cutoffAt");
    Instant slaDeadlineAt = instantValue(row, "slaDeadlineAt");
    Instant now = BatchDateTimeSupport.utcNow();
    Long timeUntilCutoffSeconds =
        cutoffAt == null ? null : ChronoUnit.SECONDS.between(now, cutoffAt);
    Instant lateArrivalWindowClosesAt =
        resolveLateArrivalWindowClosesAt(tenantId, calendarCode, cutoffAt);
    List<ConsoleBatchDaySummaryResponse> jobs =
        loadBatchDaySummaries(tenantId, calendarCode, bizDate);
    return new ConsoleBatchDayWindowResponse(
        bizDate,
        stringValue(row, "dayStatus"),
        cutoffAt,
        slaDeadlineAt,
        now,
        timeUntilCutoffSeconds,
        lateArrivalWindowClosesAt,
        jobs);
  }

  private List<ConsoleBatchDaySummaryResponse> loadBatchDaySummaries(
      String tenantId, String calendarCode, LocalDate bizDate) {
    if (bizDate == null) {
      return List.of();
    }
    List<Map<String, Object>> rows =
        opsMappers.batchDayMapper.selectJobSummaries(tenantId, calendarCode, bizDate);
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream().map(this::toBatchDaySummaryResponse).toList();
  }

  private ConsoleBatchDaySummaryResponse toBatchDaySummaryResponse(Map<String, Object> row) {
    return new ConsoleBatchDaySummaryResponse(
        stringValue(row, "jobCode"),
        intValue(row, "totalJobCount"),
        intValue(row, "successJobCount"),
        intValue(row, "failedJobCount"),
        intValue(row, "inFlightJobCount"),
        intValue(row, "catchupCount"));
  }

  private int safeInt(Integer value) {
    return value == null ? 0 : value;
  }

  private Instant resolveLateArrivalWindowClosesAt(
      String tenantId, String calendarCode, Instant cutoffAt) {
    if (cutoffAt == null) {
      return null;
    }
    Map<String, Object> calendar =
        opsMappers.businessCalendarMapper.selectActiveByTenantAndCalendarCode(
            tenantId, calendarCode);
    Integer tolerance = intValue(calendar, "lateArrivalToleranceMin");
    if (tolerance == null || tolerance <= 0) {
      return cutoffAt;
    }
    return cutoffAt.plusSeconds(tolerance * 60L);
  }

  private String resolveSlaStatus(Instant slaDeadlineAt, Instant settledAt) {
    if (slaDeadlineAt == null) {
      return "NO_SLA";
    }
    Instant now = BatchDateTimeSupport.utcNow();
    if (settledAt != null) {
      return settledAt.isAfter(slaDeadlineAt) ? "SLA_BREACH" : "ON_TIME";
    }
    return now.isAfter(slaDeadlineAt) ? "SLA_TIMEOUT" : "ON_TIME";
  }

  private ConsoleAlertEventResponse toAlertEventResponse(AlertEventEntity entity) {
    return new ConsoleAlertEventResponse(
        entity.getId(),
        display(entity.getTenantId()),
        display(entity.getServiceName()),
        display(entity.getAlertType()),
        display(entity.getSeverity()),
        display(entity.getTitle()),
        display(entity.getDetailJson()),
        display(entity.getDedupFingerprint()),
        entity.getOccurrenceCount(),
        entity.getFirstSeenAt(),
        entity.getLastSeenAt(),
        display(entity.getTraceId()),
        display(entity.getStatus()),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private ConsoleDeadLetterTaskResponse toDeadLetterTaskResponse(DeadLetterTaskEntity entity) {
    return new ConsoleDeadLetterTaskResponse(
        entity.getId(),
        display(entity.getTenantId()),
        display(entity.getSourceType()),
        entity.getSourceId(),
        display(entity.getDeadLetterReason()),
        display(entity.getPayloadRef()),
        display(entity.getReplayStatus()),
        entity.getReplayCount(),
        entity.getLastReplayAt(),
        display(entity.getLastReplayResult()),
        display(entity.getTraceId()),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private ConsoleRetryScheduleResponse toRetryScheduleResponse(RetryScheduleEntity entity) {
    String lastErrorMessage =
        localizedErrorRenderer.render(
            entity.getLastErrorKey(), entity.getLastErrorArgs(), entity.getLastErrorMessage());
    return new ConsoleRetryScheduleResponse(
        entity.getId(),
        display(entity.getTenantId()),
        display(entity.getRelatedType()),
        entity.getRelatedId(),
        display(entity.getRetryPolicy()),
        entity.getRetryCount(),
        entity.getMaxRetryCount(),
        entity.getNextRetryAt(),
        display(entity.getRetryStatus()),
        display(entity.getDedupKey()),
        display(entity.getLastErrorCode()),
        display(lastErrorMessage),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private ConsolePendingCatchUpResponse toPendingCatchUpResponse(PendingCatchUpEntity entity) {
    return new ConsolePendingCatchUpResponse(
        entity.getId(),
        display(entity.getTenantId()),
        display(entity.getRequestId()),
        display(entity.getJobCode()),
        entity.getBizDate(),
        display(entity.getRequestStatus()),
        display(entity.getTraceId()),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getApprovalNo(),
        entity.getApprovalStatus());
  }

  private ConsoleWorkerRegistryResponse toWorkerRegistryResponse(WorkerRegistryEntity entity) {
    return new ConsoleWorkerRegistryResponse(
        entity.getId(),
        display(entity.getTenantId()),
        display(entity.getWorkerCode()),
        display(entity.getWorkerGroup()),
        null,
        null,
        display(entity.getStatus()),
        entity.getHeartbeatAt(),
        null,
        entity.getDrainStartedAt(),
        entity.getDrainDeadlineAt());
  }
}
