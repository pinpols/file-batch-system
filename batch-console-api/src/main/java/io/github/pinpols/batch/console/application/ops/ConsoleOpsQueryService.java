package io.github.pinpols.batch.console.application.ops;

import static io.github.pinpols.batch.console.shared.query.ConsoleQuerySupport.*;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.dto.WorkerTaskCapabilityDto;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.application.contract.query.ApprovalCommandQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.AuditLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.OutboxDeliveryLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.OutboxRetryLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.RetryScheduleQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.WorkerRegistryQueryRequest;
import io.github.pinpols.batch.console.domain.audit.application.contract.query.ConsoleAiAuditLogQueryRequest;
import io.github.pinpols.batch.console.domain.audit.application.contract.response.AiAuditLogResponse;
import io.github.pinpols.batch.console.domain.audit.entity.ConsoleAiAuditLogEntity;
import io.github.pinpols.batch.console.domain.audit.query.ConsoleAiAuditLogQuery;
import io.github.pinpols.batch.console.domain.audit.view.FileAuditLogRow;
import io.github.pinpols.batch.console.domain.governance.application.contract.query.DeadLetterQueryRequest;
import io.github.pinpols.batch.console.domain.governance.application.contract.response.ConsoleDeadLetterTaskResponse;
import io.github.pinpols.batch.console.domain.governance.entity.DeadLetterTaskEntity;
import io.github.pinpols.batch.console.domain.governance.query.DeadLetterTaskQuery;
import io.github.pinpols.batch.console.domain.job.application.contract.query.BatchDayQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.BatchDayWindowQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.PendingCatchUpQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDaySummaryResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayWindowResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleRetryScheduleResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleScheduledOccurrenceResponse;
import io.github.pinpols.batch.console.domain.job.entity.PendingCatchUpEntity;
import io.github.pinpols.batch.console.domain.job.query.BatchDayQuery;
import io.github.pinpols.batch.console.domain.job.query.PendingCatchUpQuery;
import io.github.pinpols.batch.console.domain.job.view.BatchDayJobSummaryView;
import io.github.pinpols.batch.console.domain.job.view.BatchDayScheduledOccurrenceView;
import io.github.pinpols.batch.console.domain.job.view.BatchDayView;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.ActiveCalendarRow;
import io.github.pinpols.batch.console.domain.notification.application.contract.query.AlertEventQueryRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.domain.notification.query.AlertEventQuery;
import io.github.pinpols.batch.console.domain.ops.entity.ApprovalCommandEntity;
import io.github.pinpols.batch.console.domain.ops.entity.RetryScheduleEntity;
import io.github.pinpols.batch.console.domain.ops.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.console.domain.ops.query.ApprovalCommandQuery;
import io.github.pinpols.batch.console.domain.ops.query.OutboxDeliveryLogCountQuery;
import io.github.pinpols.batch.console.domain.ops.query.OutboxDeliveryLogQuery;
import io.github.pinpols.batch.console.domain.ops.query.OutboxRetryLogQuery;
import io.github.pinpols.batch.console.domain.ops.query.RetryScheduleQuery;
import io.github.pinpols.batch.console.domain.ops.query.WorkerRegistryQuery;
import io.github.pinpols.batch.console.domain.ops.view.OutboxDeliveryLogRow;
import io.github.pinpols.batch.console.domain.ops.view.OutboxRetryLogRow;
import io.github.pinpols.batch.console.domain.query.AuditLogQuery;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.view.ConsoleApprovalCommandResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleAuditLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleOutboxDeliveryLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleOutboxRetryLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsolePendingCatchUpResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleWorkerRegistryResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/** 控制台运维查询应用服务，聚合各领域的只读投影。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleOpsQueryService implements ConsoleOpsQueryPort {

  private final TenantIdResolver tenantGuard;
  private final ConsoleOpsQueryMappers opsMappers;
  private final LocalizedErrorRenderer localizedErrorRenderer;
  private final BatchTimezoneProvider timezoneProvider;

  @Override
  public PageResponse<ConsoleAuditLogResponse> auditLogs(AuditLogQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    AuditLogQuery query = new AuditLogQuery();
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    query.setTenantId(tenantId);
    query.setOperationType(request.getOperationType());
    query.setOperationResult(request.getOperationResult());
    query.setOperatorId(request.getOperatorId());
    String fileIdText = request.getFileId();
    Long fileId = parseLong(fileIdText, "fileId");
    query.setFileId(fileId);
    query.setTraceId(request.getTraceId());
    String fromTimeText = firstNonBlank(request.getFromTime(), request.getStartTime());
    ZoneId fromTimeZone = timezoneProvider.defaultZone();
    Instant fromTime = parseFlexibleInstant(fromTimeText, "fromTime", fromTimeZone);
    query.setFromTime(fromTime);
    String toTimeText = firstNonBlank(request.getToTime(), request.getEndTime());
    ZoneId toTimeZone = timezoneProvider.defaultZone();
    Instant toTime = parseFlexibleInstant(toTimeText, "toTime", toTimeZone);
    query.setToTime(toTime);
    query.setPageRequest(pageRequest);
    String cursor = request.getCursor();
    Long cursorId = decodeCursorId(cursor);
    query.setCursorId(cursorId);
    List<FileAuditLogRow> rows = opsMappers.auditLogMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(pageRequest, rows, this::toAuditLogResponse, FileAuditLogRow::id);
    }
    long total = opsMappers.auditLogMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toAuditLogResponse);
  }

  @Override
  public PageResponse<ConsoleAuditLogResponse> executionLogs(AuditLogQueryRequest request) {
    return auditLogs(request);
  }

  @Override
  public PageResponse<ConsoleOutboxRetryLogResponse> outboxRetries(
      OutboxRetryLogQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    String retryStatus = request.getRetryStatus();
    String eventKey = request.getEventKey();
    String cursor = request.getCursor();
    Long cursorId = decodeCursorId(cursor);
    OutboxRetryLogQuery query = OutboxRetryLogQuery.builder()
        .tenantId(tenantId)
        .retryStatus(retryStatus)
        .eventKey(eventKey)
        .eventType(request.getEventType())
        .pageRequest(pageRequest)
        .cursorId(cursorId)
        .build();
    List<OutboxRetryLogRow> rows = opsMappers.outboxRetryLogMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(pageRequest, rows, this::toOutboxRetryResponse, OutboxRetryLogRow::id);
    }
    long total = opsMappers.outboxRetryLogMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toOutboxRetryResponse);
  }

  @Override
  public PageResponse<ConsoleOutboxDeliveryLogResponse> outboxDeliveries(
      OutboxDeliveryLogQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    String deliveryStatus = request.getDeliveryStatus();
    String eventType = request.getEventType();
    String eventKey = request.getEventKey();
    String traceId = request.getTraceId();
    String cursor = request.getCursor();
    Long cursorId = decodeCursorId(cursor);
    OutboxDeliveryLogQuery query = OutboxDeliveryLogQuery.builder()
        .tenantId(tenantId)
        .deliveryStatus(deliveryStatus)
        .eventType(eventType)
        .eventKey(eventKey)
        .targetTopic(request.getTargetTopic())
        .traceId(traceId)
        .pageRequest(pageRequest)
        .cursorId(cursorId)
        .build();
    List<OutboxDeliveryLogRow> rows = opsMappers.outboxDeliveryLogMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(
          pageRequest, rows, this::toOutboxDeliveryResponse, OutboxDeliveryLogRow::id);
    }
    OutboxDeliveryLogCountQuery outboxDeliveryLogCountQuery = OutboxDeliveryLogCountQuery.builder()
        .tenantId(query.tenantId())
        .deliveryStatus(query.deliveryStatus())
        .eventType(query.eventType())
        .eventKey(query.eventKey())
        .targetTopic(query.targetTopic())
        .traceId(query.traceId())
        .pageRequest(query.pageRequest())
        .build();
    long total = opsMappers.outboxDeliveryLogMapper.countByQuery(outboxDeliveryLogCountQuery);
    return page(pageRequest, total, rows, this::toOutboxDeliveryResponse);
  }

  @Override
  public PageResponse<AiAuditLogResponse> aiAuditLogs(ConsoleAiAuditLogQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String requestedTenantId = request.getTenantId();
    String tenantId = resolveTenant(tenantGuard, requestedTenantId);
    String sessionId = request.getSessionId();
    String operatorId = request.getOperatorId();
    String promptCategory = request.getPromptCategory();
    String promptDecision = request.getPromptDecision();
    String fromTimeText = request.getFromTime();
    Instant fromTime = parseInstant(fromTimeText, "fromTime");
    String toTimeText = request.getToTime();
    Instant toTime = parseInstant(toTimeText, "toTime");
    Long cursorId = decodeCursorId(request.getCursor());
    ConsoleAiAuditLogQuery query = new ConsoleAiAuditLogQuery(
        tenantId,
        sessionId,
        operatorId,
        promptCategory,
        promptDecision,
        fromTime,
        toTime,
        pageRequest,
        cursorId);
    List<ConsoleAiAuditLogEntity> rows = opsMappers.consoleAiAuditLogMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(
          pageRequest, rows, this::toAiAuditLogResponse, ConsoleAiAuditLogEntity::getId);
    }
    long total = opsMappers.consoleAiAuditLogMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toAiAuditLogResponse);
  }

  @Override
  public PageResponse<ConsoleDeadLetterTaskResponse> deadLetters(DeadLetterQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String requestedTenantId = request.getTenantId();
    String tenantId = resolveTenant(tenantGuard, requestedTenantId);
    String sourceType = request.getSourceType();
    String replayStatus = request.getReplayStatus();
    String traceId = request.getTraceId();
    Long cursorId = decodeCursorId(request.getCursor());
    DeadLetterTaskQuery query =
        new DeadLetterTaskQuery(tenantId, sourceType, replayStatus, traceId, pageRequest, cursorId);
    List<DeadLetterTaskEntity> rows = opsMappers.deadLetterTaskMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(
          pageRequest, rows, this::toDeadLetterTaskResponse, DeadLetterTaskEntity::getId);
    }
    long total = opsMappers.deadLetterTaskMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toDeadLetterTaskResponse);
  }

  @Override
  public PageResponse<ConsoleRetryScheduleResponse> retries(RetryScheduleQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String requestedTenantId = request.getTenantId();
    String tenantId = resolveTenant(tenantGuard, requestedTenantId);
    String relatedType = request.getRelatedType();
    String retryPolicy = request.getRetryPolicy();
    String retryStatus = request.getRetryStatus();
    Long cursorId = decodeCursorId(request.getCursor());
    RetryScheduleQuery query = new RetryScheduleQuery(
        tenantId, relatedType, retryPolicy, retryStatus, pageRequest, cursorId, cursorMode);
    List<RetryScheduleEntity> rows = opsMappers.retryScheduleMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(
          pageRequest, rows, this::toRetryScheduleResponse, RetryScheduleEntity::getId);
    }
    long total = opsMappers.retryScheduleMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toRetryScheduleResponse);
  }

  @Override
  public PageResponse<ConsolePendingCatchUpResponse> pendingCatchUps(
      PendingCatchUpQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String requestedTenantId = request.getTenantId();
    String tenantId = resolveTenant(tenantGuard, requestedTenantId);
    String workflowCode = request.getWorkflowCode();
    String requestId = request.getRequestId();
    String bizDate = request.getBizDate();
    String keyword = request.getKeyword();
    Long cursorId = decodeCursorId(request.getCursor());
    PendingCatchUpQuery query = new PendingCatchUpQuery(
        tenantId, workflowCode, requestId, bizDate, keyword, pageRequest, cursorId);
    List<PendingCatchUpEntity> rows = opsMappers.pendingCatchUpMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(
          pageRequest, rows, this::toPendingCatchUpResponse, PendingCatchUpEntity::getId);
    }
    long total = opsMappers.pendingCatchUpMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toPendingCatchUpResponse);
  }

  @Override
  public PageResponse<ConsoleWorkerRegistryResponse> workers(WorkerRegistryQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String requestedTenantId = request.getTenantId();
    String tenantId = resolveTenant(tenantGuard, requestedTenantId);
    String workerGroup = request.getWorkerGroup();
    String status = request.getStatus();
    WorkerRegistryQuery query = new WorkerRegistryQuery(tenantId, workerGroup, status, pageRequest);
    List<WorkerRegistryEntity> rows = opsMappers.workerRegistryMapper.selectByQuery(query);
    long total = opsMappers.workerRegistryMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toWorkerRegistryResponse);
  }

  @Override
  public PageResponse<ConsoleAlertEventResponse> alertEvents(AlertEventQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    String severity = request.getSeverity();
    String status = request.getStatus();
    String alertType = request.getAlertType();
    String traceId = request.getTraceId();
    String startDateText = request.getStartDate();
    ZoneId startDateZone = timezoneProvider.defaultZone();
    Instant startDate = parseFlexibleInstant(startDateText, "startDate", startDateZone);
    String endDateText = request.getEndDate();
    ZoneId endDateZone = timezoneProvider.defaultZone();
    Instant endDate = parseFlexibleInstant(endDateText, "endDate", endDateZone);
    String cursor = request.getCursor();
    Long cursorId = decodeCursorId(cursor);
    AlertEventQuery query = new AlertEventQuery(
        tenantId,
        severity,
        status,
        alertType,
        traceId,
        startDate,
        endDate,
        pageRequest,
        cursorId,
        cursorMode);
    List<AlertEventEntity> rows = opsMappers.alertEventMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(pageRequest, rows, this::toAlertEventResponse, AlertEventEntity::getId);
    }
    long total = opsMappers.alertEventMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toAlertEventResponse);
  }

  @Override
  public PageResponse<ConsoleBatchDayResponse> batchDays(BatchDayQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    LocalDate fromBizDate = parseLocalDate(request.getFrom(), "from");
    LocalDate toBizDate = parseLocalDate(request.getTo(), "to");
    BatchDayQuery batchDayQuery = BatchDayQuery.builder()
        .tenantId(tenantId)
        .calendarCode(request.getCalendarCode())
        .fromBizDate(fromBizDate)
        .toBizDate(toBizDate)
        .pageRequest(pageRequest)
        .build();
    List<BatchDayView> rows = opsMappers.batchDayMapper.selectByQuery(batchDayQuery);
    long total = opsMappers.batchDayMapper.countByQuery(
        tenantId, request.getCalendarCode(), fromBizDate, toBizDate);
    List<ConsoleBatchDayResponse> responses = rows.stream()
        .map(row -> toBatchDayResponse(tenantId, request.getCalendarCode(), row))
        .toList();
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), responses);
  }

  @Override
  public ConsoleBatchDayWindowResponse batchDayWindow(
      String bizDate, BatchDayWindowQueryRequest request) {
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    LocalDate parsedBizDate = parseLocalDate(bizDate, "bizDate");
    String calendarCode = request.getCalendarCode();
    BatchDayView batchDayWindow =
        opsMappers.batchDayMapper.selectWindow(tenantId, calendarCode, parsedBizDate);
    return toBatchDayWindowResponse(tenantId, calendarCode, parsedBizDate, batchDayWindow);
  }

  @Override
  public PageResponse<ConsoleApprovalCommandResponse> approvals(
      ApprovalCommandQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    ApprovalCommandQuery query = new ApprovalCommandQuery();
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    query.setTenantId(tenantId);
    query.setApprovalNo(request.getApprovalNo());
    query.setApprovalType(request.getApprovalType());
    query.setActionType(request.getActionType());
    query.setApprovalStatus(request.getApprovalStatus());
    query.setRequesterId(request.getRequesterId());
    query.setKeyword(request.getKeyword());
    query.setPageRequest(pageRequest);
    List<ApprovalCommandEntity> rows = opsMappers.approvalCommandMapper.selectByQuery(query);
    long total = opsMappers.approvalCommandMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toApprovalResponse);
  }

  private ConsoleAuditLogResponse toAuditLogResponse(FileAuditLogRow row) {
    return ConsoleAuditLogResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .fileId(row.fileId())
        .operationType(row.operationType())
        .operationResult(row.operationResult())
        .operatorType(row.operatorType())
        .operatorId(row.operatorId())
        .traceId(row.traceId())
        .evidenceRef(row.evidenceRef())
        .detailSummary(row.detailSummary())
        .createdAt(row.createdAt())
        .build();
  }

  private ConsoleOutboxRetryLogResponse toOutboxRetryResponse(OutboxRetryLogRow row) {
    return ConsoleOutboxRetryLogResponse.builder()
        .id(row.id())
        .outboxEventId(row.outboxEventId())
        .tenantId(row.tenantId())
        .eventType(row.eventType())
        .eventKey(row.eventKey())
        .retryStatus(row.retryStatus())
        .retryCount(row.retryCount())
        .retryPolicy(row.retryPolicy())
        .nextRetryAt(row.nextRetryAt())
        .createdAt(row.createdAt())
        .updatedAt(row.updatedAt())
        .build();
  }

  private ConsoleOutboxDeliveryLogResponse toOutboxDeliveryResponse(OutboxDeliveryLogRow row) {
    String errorKey = row.errorKey();
    String displayableErrorArgs = SecretMasking.maskSensitiveJson(row.errorArgs());
    String fallbackErrorMessage = row.errorMessage();
    String renderedError =
        localizedErrorRenderer.render(errorKey, displayableErrorArgs, fallbackErrorMessage);
    String errorMessage = safeOutboxDeliveryError(renderedError);
    return ConsoleOutboxDeliveryLogResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .eventType(row.eventType())
        .eventKey(row.eventKey())
        .traceId(row.traceId())
        .deliveryStatus(row.deliveryStatus())
        .targetTopic(row.targetTopic())
        .deliveryAttempt(row.deliveryAttempt())
        .errorMessage(errorMessage)
        .createdAt(row.createdAt())
        .updatedAt(row.updatedAt())
        .build();
  }

  private static String safeOutboxDeliveryError(String value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String normalized = ConsoleTextSanitizer.normalize(HtmlUtils.htmlUnescape(value));
    if (!SwallowedExceptionLogger.containsCredentialSyntax(normalized)) {
      return value;
    }
    int maxLength = Math.max(4, normalized.length());
    return SwallowedExceptionLogger.safeMessage(normalized, maxLength);
  }

  private AiAuditLogResponse toAiAuditLogResponse(ConsoleAiAuditLogEntity entity) {
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
    String promptPreview = entity.getPromptPreview();
    String safePromptPreview = ConsoleTextSanitizer.safeDisplay(promptPreview, 512);
    row.setPromptPreview(safePromptPreview);
    String responsePreview = entity.getResponsePreview();
    String safeResponsePreview = ConsoleTextSanitizer.safeDisplay(responsePreview, 512);
    row.setResponsePreview(safeResponsePreview);
    String refusalReason = entity.getRefusalReason();
    String safeRefusalReason = ConsoleTextSanitizer.safeDisplay(refusalReason, 512);
    row.setRefusalReason(safeRefusalReason);
    row.setPromptTokens(entity.getPromptTokens());
    row.setCompletionTokens(entity.getCompletionTokens());
    row.setEstimatedCostUsd(entity.getEstimatedCostUsd());
    row.setCostStatus(entity.getCostStatus());
    row.setCreatedAt(entity.getCreatedAt());
    return row;
  }

  private ConsoleApprovalCommandResponse toApprovalResponse(ApprovalCommandEntity entity) {
    String rawPayloadJson = entity.getPayloadJson();
    String maskedPayloadJson = SecretMasking.maskSensitiveJson(rawPayloadJson);
    Long id = entity.getId();
    String tenantId = display(entity.getTenantId());
    String approvalNo = display(entity.getApprovalNo());
    String approvalType = display(entity.getApprovalType());
    String actionType = display(entity.getActionType());
    String targetType = display(entity.getTargetType());
    String targetId = display(entity.getTargetId());
    String approvalStatus = display(entity.getApprovalStatus());
    String requesterId = display(entity.getRequesterId());
    String approverId = display(entity.getApproverId());
    String rejectionReason = display(entity.getRejectionReason());
    String approvalReason = display(entity.getApprovalReason());
    String sourceTraceId = display(entity.getSourceTraceId());
    String sourceIdempotencyKey = display(entity.getSourceIdempotencyKey());
    OffsetDateTime approvedAt = entity.getApprovedAt();
    OffsetDateTime executedAt = entity.getExecutedAt();
    OffsetDateTime createdAt = entity.getCreatedAt();
    OffsetDateTime updatedAt = entity.getUpdatedAt();
    return ConsoleApprovalCommandResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .approvalNo(approvalNo)
        .approvalType(approvalType)
        .actionType(actionType)
        .targetType(targetType)
        .targetId(targetId)
        .payloadJson(maskedPayloadJson)
        .approvalStatus(approvalStatus)
        .requesterId(requesterId)
        .approverId(approverId)
        .rejectionReason(rejectionReason)
        .approvalReason(approvalReason)
        .sourceTraceId(sourceTraceId)
        .sourceIdempotencyKey(sourceIdempotencyKey)
        .approvedAt(approvedAt)
        .executedAt(executedAt)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .build();
  }

  private ConsoleBatchDayResponse toBatchDayResponse(
      String tenantId, String calendarCode, BatchDayView row) {
    LocalDate bizDate = row.bizDate();
    List<ConsoleBatchDaySummaryResponse> summaries =
        loadBatchDaySummaries(tenantId, calendarCode, bizDate);
    List<ConsoleBatchDaySummaryResponse> catchupSummary = summaries.stream()
        .filter(
            summary -> safeInt(summary.failedJobCount()) > 0 || safeInt(summary.catchupCount()) > 0)
        .toList();
    String dayStatus = row.dayStatus();
    Instant openAt = row.openAt();
    Instant cutoffAt = row.cutoffAt();
    Instant settledAt = row.settledAt();
    Instant slaDeadlineAt = row.slaDeadlineAt();
    String slaStatus = resolveSlaStatus(slaDeadlineAt, settledAt);
    return ConsoleBatchDayResponse.builder()
        .bizDate(bizDate)
        .dayStatus(dayStatus)
        .openAt(openAt)
        .cutoffAt(cutoffAt)
        .settledAt(settledAt)
        .slaDeadlineAt(slaDeadlineAt)
        .slaStatus(slaStatus)
        .totalJobCount(intValue(row.totalJobCount()))
        .successJobCount(intValue(row.successJobCount()))
        .failedJobCount(intValue(row.failedJobCount()))
        .inFlightJobCount(intValue(row.inFlightJobCount()))
        .lateCount(row.lateCount())
        .catchupCount(row.catchupCount())
        .catchupSummary(catchupSummary)
        .build();
  }

  private ConsoleBatchDayWindowResponse toBatchDayWindowResponse(
      String tenantId, String calendarCode, LocalDate bizDate, BatchDayView row) {
    Instant cutoffAt = row == null ? null : row.cutoffAt();
    Instant slaDeadlineAt = row == null ? null : row.slaDeadlineAt();
    Instant now = BatchDateTimeSupport.utcNow();
    Long timeUntilCutoffSeconds =
        cutoffAt == null ? null : ChronoUnit.SECONDS.between(now, cutoffAt);
    Instant lateArrivalWindowClosesAt =
        resolveLateArrivalWindowClosesAt(tenantId, calendarCode, cutoffAt);
    List<ConsoleBatchDaySummaryResponse> jobs =
        loadBatchDaySummaries(tenantId, calendarCode, bizDate);
    List<ConsoleScheduledOccurrenceResponse> scheduledOccurrences =
        opsMappers
            .batchDayMapper
            .selectScheduledOccurrences(tenantId, calendarCode, bizDate)
            .stream()
            .map(this::toScheduledOccurrenceResponse)
            .toList();
    return new ConsoleBatchDayWindowResponse(
        bizDate,
        row == null ? null : row.dayStatus(),
        cutoffAt,
        slaDeadlineAt,
        now,
        timeUntilCutoffSeconds,
        lateArrivalWindowClosesAt,
        jobs,
        scheduledOccurrences,
        EmptyChecks.isNull(row) ? null : row.frozen());
  }

  private List<ConsoleBatchDaySummaryResponse> loadBatchDaySummaries(
      String tenantId, String calendarCode, LocalDate bizDate) {
    if (bizDate == null) {
      return List.of();
    }
    List<BatchDayJobSummaryView> rows =
        opsMappers.batchDayMapper.selectJobSummaries(tenantId, calendarCode, bizDate);
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream().map(this::toBatchDaySummaryResponse).toList();
  }

  private ConsoleBatchDaySummaryResponse toBatchDaySummaryResponse(BatchDayJobSummaryView row) {
    int totalJobCount = intValue(row.totalJobCount());
    int successJobCount = intValue(row.successJobCount());
    int failedJobCount = intValue(row.failedJobCount());
    int inFlightJobCount = intValue(row.inFlightJobCount());
    int catchupCount = intValue(row.catchupCount());
    return new ConsoleBatchDaySummaryResponse(
        row.jobCode(),
        totalJobCount,
        successJobCount,
        failedJobCount,
        inFlightJobCount,
        catchupCount);
  }

  private ConsoleScheduledOccurrenceResponse toScheduledOccurrenceResponse(
      BatchDayScheduledOccurrenceView occurrence) {
    return new ConsoleScheduledOccurrenceResponse(
        occurrence.workflowDefinitionId(),
        occurrence.workflowCode(),
        occurrence.catchUpPolicy(),
        occurrence.scheduleVersion(),
        occurrence.scheduledFireTime(),
        occurrence.bizDate(),
        occurrence.occurrenceStatus(),
        occurrence.requestId(),
        occurrence.lastRejectionReason());
  }

  private Integer intValue(Number value) {
    return value == null ? null : value.intValue();
  }

  private int safeInt(Integer value) {
    return value == null ? 0 : value;
  }

  private Instant resolveLateArrivalWindowClosesAt(
      String tenantId, String calendarCode, Instant cutoffAt) {
    if (cutoffAt == null) {
      return null;
    }
    ActiveCalendarRow calendar =
        opsMappers.businessCalendarMapper.selectActiveByTenantAndCalendarCode(
            tenantId, calendarCode);
    Integer tolerance = EmptyChecks.isNull(calendar) ? null : calendar.lateArrivalToleranceMin();
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
    Long id = entity.getId();
    String tenantId = display(entity.getTenantId());
    String serviceName = display(entity.getServiceName());
    String alertType = display(entity.getAlertType());
    String severity = display(entity.getSeverity());
    String title = display(entity.getTitle());
    String detailJson = entity.getDetailJson();
    String maskedDetailJson = SecretMasking.maskSensitiveJson(detailJson);
    String displayDetailJson = ConsoleTextSanitizer.safeDisplay(maskedDetailJson);
    String dedupFingerprint = display(entity.getDedupFingerprint());
    String traceId = display(entity.getTraceId());
    String status = display(entity.getStatus());
    return ConsoleAlertEventResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .serviceName(serviceName)
        .alertType(alertType)
        .severity(severity)
        .title(title)
        .detailJson(displayDetailJson)
        .dedupFingerprint(dedupFingerprint)
        .occurrenceCount(entity.getOccurrenceCount())
        .firstSeenAt(entity.getFirstSeenAt())
        .lastSeenAt(entity.getLastSeenAt())
        .traceId(traceId)
        .status(status)
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }

  private ConsoleDeadLetterTaskResponse toDeadLetterTaskResponse(DeadLetterTaskEntity entity) {
    String tenantId = display(entity.getTenantId());
    String sourceType = display(entity.getSourceType());
    String deadLetterReason = display(entity.getDeadLetterReason());
    String errorClass = display(entity.getErrorClass());
    String payloadRef = display(entity.getPayloadRef());
    String replayStatus = display(entity.getReplayStatus());
    String lastReplayResult = display(entity.getLastReplayResult());
    String traceId = display(entity.getTraceId());
    return ConsoleDeadLetterTaskResponse.builder()
        .id(entity.getId())
        .tenantId(tenantId)
        .sourceType(sourceType)
        .sourceId(entity.getSourceId())
        .deadLetterReason(deadLetterReason)
        .errorClass(errorClass)
        .payloadRef(payloadRef)
        .replayStatus(replayStatus)
        .replayCount(entity.getReplayCount())
        .lastReplayAt(entity.getLastReplayAt())
        .lastReplayResult(lastReplayResult)
        .traceId(traceId)
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }

  private ConsoleRetryScheduleResponse toRetryScheduleResponse(RetryScheduleEntity entity) {
    String lastErrorMessage = localizedErrorRenderer.render(
        entity.getLastErrorKey(), entity.getLastErrorArgs(), entity.getLastErrorMessage());
    String tenantId = display(entity.getTenantId());
    String relatedType = display(entity.getRelatedType());
    String retryPolicy = display(entity.getRetryPolicy());
    String retryStatus = display(entity.getRetryStatus());
    String dedupKey = display(entity.getDedupKey());
    String lastErrorCode = display(entity.getLastErrorCode());
    String displayLastErrorMessage = display(lastErrorMessage);
    return ConsoleRetryScheduleResponse.builder()
        .id(entity.getId())
        .tenantId(tenantId)
        .relatedType(relatedType)
        .relatedId(entity.getRelatedId())
        .retryPolicy(retryPolicy)
        .retryCount(entity.getRetryCount())
        .maxRetryCount(entity.getMaxRetryCount())
        .nextRetryAt(entity.getNextRetryAt())
        .retryStatus(retryStatus)
        .dedupKey(dedupKey)
        .lastErrorCode(lastErrorCode)
        .lastErrorMessage(displayLastErrorMessage)
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }

  private ConsolePendingCatchUpResponse toPendingCatchUpResponse(PendingCatchUpEntity entity) {
    String tenantId = display(entity.getTenantId());
    String requestId = display(entity.getRequestId());
    String workflowCode = display(entity.getWorkflowCode());
    String requestStatus = display(entity.getRequestStatus());
    String traceId = display(entity.getTraceId());
    return ConsolePendingCatchUpResponse.builder()
        .id(entity.getId())
        .tenantId(tenantId)
        .requestId(requestId)
        .workflowCode(workflowCode)
        .bizDate(entity.getBizDate())
        .requestStatus(requestStatus)
        .traceId(traceId)
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .approvalNo(entity.getApprovalNo())
        .approvalStatus(entity.getApprovalStatus())
        .build();
  }

  private ConsoleWorkerRegistryResponse toWorkerRegistryResponse(WorkerRegistryEntity entity) {
    String tenantId = display(entity.getTenantId());
    String workerCode = display(entity.getWorkerCode());
    String workerGroup = display(entity.getWorkerGroup());
    String status = display(entity.getStatus());
    String taskCapabilitiesJson = entity.getTaskCapabilitiesJson();
    List<WorkerTaskCapabilityDto> taskCapabilities = EmptyChecks.isNull(taskCapabilitiesJson)
        ? List.of()
        : JsonUtils.fromJson(
            taskCapabilitiesJson, new TypeReference<List<WorkerTaskCapabilityDto>>() {});
    return ConsoleWorkerRegistryResponse.builder()
        .id(entity.getId())
        .tenantId(tenantId)
        .workerCode(workerCode)
        .workerGroup(workerGroup)
        .capabilityTags(entity.getCapabilityTagsJson())
        .resourceTag(entity.getResourceTag())
        .status(status)
        .heartbeatAt(entity.getHeartbeatAt())
        .currentLoad(null)
        .drainStartedAt(entity.getDrainStartedAt())
        .drainDeadlineAt(entity.getDrainDeadlineAt())
        .port(entity.getPort())
        .taskCapabilities(taskCapabilities)
        .build();
  }
}
