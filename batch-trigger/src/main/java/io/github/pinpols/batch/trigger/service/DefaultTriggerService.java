package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.dto.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.MisfirePendingStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.trigger.application.UpstreamReadinessPort;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchResponse;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchStatus;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchTarget;
import io.github.pinpols.batch.trigger.domain.ExternalEventTargetStatus;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.TriggerLaunchStatus;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchCommand;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledOccurrenceCatchUpCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.domain.entity.ExternalEventDeliveryEntity;
import io.github.pinpols.batch.trigger.event.TriggerOutboxDomainEventPublisher;
import io.github.pinpols.batch.trigger.mapper.ExternalEventDeliveryMapper;
import io.github.pinpols.batch.trigger.mapper.TenantStatusMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerMisfirePendingMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.trigger.support.CalendarBizDateDefinition;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import io.github.pinpols.batch.trigger.support.ScheduledOccurrenceObservation;
import io.github.pinpols.batch.trigger.web.request.ExternalEventLaunchRequest;
import io.github.pinpols.batch.trigger.web.request.ScheduledOccurrenceCatchUpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 触发层核心服务，负责校验 → 持久化 trigger_request → 转发给 Orchestrator，提供 4 条入口：
 *
 * <ul>
 *   <li>{@link #launch} — API 手工触发，校验 idempotencyKey + bizDate + triggerType 后调用 {@link
 *       #persistAndForward}。
 *   <li>{@link #launchScheduled} — Quartz 定时触发，先解析业务日历得到 bizDate；若 bizDate=null
 *       表示日历标记为节假日+SKIP，直接跳过不产生 trigger_request。
 *   <li>{@link #createPendingCatchUp} — CatchUpPolicy=MANUAL_APPROVAL 路径，把请求以 {@code ACCEPTED}
 *       状态写入数据库等待人工审批，不立即转给 Orchestrator。
 *   <li>{@link #approvePendingCatchUp} — 人工审批通过后补跑：CAS 将 {@code ACCEPTED →
 *       PROCESSING}（防并发双审批），在同事务写入 Outbox。实际创建根运行后由 Orchestrator 更新为 {@code LAUNCHED}。
 * </ul>
 *
 * <p><b>持久化与转发模式（{@link #persistAndForward}）</b>：在一个 {@code PROPAGATION_REQUIRES_NEW}
 * 事务内完成去重查询、以 ACCEPTED 写入 trigger_request、写入 trigger_outbox_event；提交后
 * relay 周期发 Kafka，orchestrator 端 consumer 异步执行 launch。
 * 通用请求按 tenant + Idempotency-Key 去重；外部事件另由数据库唯一约束按 tenant + Workflow + source + event ID
 * 保证投递幂等。Orchestrator 按租户和请求去重根 WorkflowRun，JobInstance 去重保护节点执行。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultTriggerService implements TriggerService {
  private static final String EXTERNAL_EVENT_IDENTITY_CONFLICT = "error.trigger.external_event_identity_conflict";
  private static final String REQUEST_BODY_REQUIRED = "error.common.request_body_required";

  private static final String CLOSED_BATCH_DAY_SQL_STATE = "BDA01";

  private final LaunchAdapterService launchAdapterService;
  private final TriggerRequestMapper triggerRequestMapper;
  private final ExternalEventDeliveryMapper externalEventDeliveryMapper;
  private final TriggerMisfirePendingMapper triggerMisfirePendingMapper;
  private final TriggerOutboxDomainEventPublisher triggerOutboxPublisher;
  private final TriggerCalendarDefinitionProvider calendarDefinitionProvider;
  private final ScheduledOccurrenceRecorder occurrenceRecorder;
  private final TenantStatusMapper tenantStatusMapper;
  private final PlatformTransactionManager transactionManager;
  private final UpstreamReadinessPort upstreamReadiness;
  private final TriggerRuntimeProperties triggerRuntimeProperties;
  private final TriggerDefinitionLoader triggerDefinitionLoader;

  private record PendingApprovalTarget(
      TriggerRequestEntity request,
      Long pendingId,
      boolean approvePending,
      Instant scheduledFireTime) {}

  @Override
  public LaunchResponse launch(TriggerLaunchCommand command) {
    validateRequest(command);
    assertTenantActive(command.request().getTenantId());
    LaunchRequest launchRequest = launchAdapterService.fromApiRequest(command);
    try {
      return persistAndForward(launchRequest, command.idempotencyKey());
    } catch (RuntimeException failure) {
      if (!hasSqlState(failure, CLOSED_BATCH_DAY_SQL_STATE)) {
        throw failure;
      }
      // 按租户恢复名额等待，不暂停 Kafka 分区，也不更改原业务日期。
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.trigger.batch_day_closed", launchRequest.bizDate());
    }
  }

  @Override
  public ExternalEventLaunchResponse launchExternalEvent(ExternalEventLaunchCommand command) {
    validateExternalEventCommand(command);
    ExternalEventLaunchRequest request = command.request();
    assertTenantActive(request.getTenantId());
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    TransactionCallback<ExternalEventLaunchResponse> admissionTransaction =
        ignored -> admitExternalEvent(command);
    try {
      return RlsTenantContextHolder.runWithTenant(
          request.getTenantId(), () -> tx.execute(admissionTransaction));
    } catch (RuntimeException failure) {
      if (!hasSqlState(failure, CLOSED_BATCH_DAY_SQL_STATE)) {
        throw failure;
      }
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.trigger.batch_day_closed", request.getBizDate());
    }
  }

  @Override
  public ExternalEventLaunchStatus findExternalEventStatus(
      String tenantId, String eventSource, String idempotencyKey) {
    if (EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isBlank(eventSource)
        || EmptyChecks.isBlank(idempotencyKey)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    return RlsTenantContextHolder.runWithTenant(
        tenantId, () -> findExternalEventStatusInTenant(tenantId, eventSource, idempotencyKey));
  }

  private ExternalEventLaunchStatus findExternalEventStatusInTenant(
      String tenantId, String eventSource, String idempotencyKey) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    return transaction.execute(
        ignored -> loadExternalEventStatus(tenantId, eventSource, idempotencyKey));
  }

  private ExternalEventLaunchStatus loadExternalEventStatus(
      String tenantId, String eventSource, String idempotencyKey) {
    externalEventDeliveryMapper.setTenantContext(tenantId);
    ExternalEventDeliveryEntity delivery =
        externalEventDeliveryMapper.selectByAlias(tenantId, eventSource, idempotencyKey);
    Guard.requireFound(delivery, "external event delivery not found");
    List<ExternalEventTargetStatus> targets =
        externalEventDeliveryMapper.selectTargetStatuses(
            tenantId, eventSource, delivery.getEventId());
    Instant updatedAt = targets.stream()
        .map(ExternalEventTargetStatus::updatedAt)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(delivery.getCreatedAt());
    return new ExternalEventLaunchStatus(
        delivery.getRequestId(),
        delivery.getEventSource(),
        delivery.getEventId(),
        delivery.getEventCode(),
        delivery.getBizDate(),
        delivery.getTraceId(),
        targets,
        updatedAt);
  }

  private ExternalEventLaunchResponse admitExternalEvent(ExternalEventLaunchCommand command) {
    ExternalEventLaunchRequest request = command.request();
    externalEventDeliveryMapper.setTenantContext(request.getTenantId());
    triggerRequestMapper.acquireIdempotencyFence(request.getTenantId(), command.idempotencyKey());
    TriggerRequestEntity existingRequest = triggerRequestMapper.selectByTenantAndDedupKey(
        request.getTenantId(), command.idempotencyKey());
    if (EmptyChecks.isNotNull(existingRequest)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
    triggerRequestMapper.acquireExternalEventIdentityFence(
        request.getTenantId(), command.eventSource(), request.getExternalEventId());
    ExternalEventDeliveryEntity existing = externalEventDeliveryMapper.selectByIdentityForUpdate(
        request.getTenantId(), command.eventSource(), request.getExternalEventId());
    if (EmptyChecks.isNotNull(existing)) {
      assertSameExternalEventPayload(existing, request);
      registerExternalEventDeliveryAlias(existing, command.idempotencyKey());
      return externalEventLaunchResponse(existing);
    }

    List<Long> targets = triggerRequestMapper.selectEnabledExternalEventWorkflowTargets(
        request.getTenantId(), request.getExternalEventCode());
    if (EmptyChecks.isEmpty(targets)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.trigger.external_event_target_not_found");
    }
    List<Long> existingWorkflowTargets =
        triggerRequestMapper.selectWorkflowTargetsByExternalEventIdentity(
            request.getTenantId(), command.eventSource(), request.getExternalEventId());
    if (EmptyChecks.isNotEmpty(existingWorkflowTargets)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
    for (Long workflowDefinitionId : targets) {
      triggerRequestMapper.acquireBatchDaySettlementFence(
          request.getTenantId(), workflowDefinitionId, null, request.getBizDate());
    }

    ExternalEventDeliveryEntity delivery = new ExternalEventDeliveryEntity();
    delivery.setTenantId(request.getTenantId());
    delivery.setEventSource(command.eventSource());
    delivery.setEventId(request.getExternalEventId());
    delivery.setEventCode(request.getExternalEventCode());
    delivery.setBizDate(request.getBizDate());
    delivery.setRequestId(command.requestId());
    String payloadSnapshot = JsonUtils.toJson(request);
    delivery.setPayloadSnapshot(payloadSnapshot);
    String targetWorkflowDefinitionIds = JsonUtils.toJson(targets);
    delivery.setTargetWorkflowDefinitionIds(targetWorkflowDefinitionIds);
    delivery.setTraceId(command.traceId());
    if (externalEventDeliveryMapper.insert(delivery) == 0) {
      delivery = externalEventDeliveryMapper.selectByIdentityForUpdate(
          request.getTenantId(), command.eventSource(), request.getExternalEventId());
      Guard.requireFound(delivery, "concurrent external event delivery not found");
      assertSameExternalEventPayload(delivery, request);
      registerExternalEventDeliveryAlias(delivery, command.idempotencyKey());
      return externalEventLaunchResponse(delivery);
    }
    registerExternalEventDeliveryAlias(delivery, command.idempotencyKey());

    for (Long workflowDefinitionId : targets) {
      String requestId = IdGenerator.newBusinessNo("req");
      LaunchRequest launchRequest =
          createExternalEventLaunchRequest(command, workflowDefinitionId, requestId);
      String dedupKey = externalEventTargetDedupKey(
          request.getTenantId(),
          command.eventSource(),
          request.getExternalEventId(),
          workflowDefinitionId);
      TriggerRequestEntity acceptedRequest = buildAcceptedEntity(launchRequest, dedupKey);
      int inserted = triggerRequestMapper.insert(acceptedRequest);
      if (inserted == 0) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
      }
      publishLaunchOutbox(launchRequest, dedupKey);
    }
    return externalEventLaunchResponse(delivery);
  }

  private LaunchRequest createExternalEventLaunchRequest(
      ExternalEventLaunchCommand command, Long workflowDefinitionId, String requestId) {
    ExternalEventLaunchRequest request = command.request();
    return LaunchRequest.builder()
        .tenantId(request.getTenantId())
        .workflowDefinitionId(workflowDefinitionId)
        .bizDate(request.getBizDate())
        .triggerType(TriggerType.EVENT)
        .requestId(requestId)
        .traceId(command.traceId())
        .params(request.getParams())
        .dataIntervalStart(request.getDataIntervalStart())
        .dataIntervalEnd(request.getDataIntervalEnd())
        .dryRun(Boolean.TRUE.equals(request.getDryRun()))
        .externalEventCode(request.getExternalEventCode())
        .externalEventSource(command.eventSource())
        .externalEventId(request.getExternalEventId())
        .build();
  }

  private void validateExternalEventCommand(ExternalEventLaunchCommand command) {
    if (EmptyChecks.isNull(command)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    ExternalEventLaunchRequest requestForValidation = command.request();
    if (EmptyChecks.isNull(requestForValidation)
        || EmptyChecks.isBlank(requestForValidation.getTenantId())
        || EmptyChecks.isBlank(command.eventSource())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    String eventSourceForLength = command.eventSource();
    String trimmedEventSourceForLength = eventSourceForLength.trim();
    if (trimmedEventSourceForLength.length() > 128) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    if (EmptyChecks.isBlank(requestForValidation.getExternalEventCode())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    String externalEventCodeForLength = requestForValidation.getExternalEventCode();
    String trimmedExternalEventCode = externalEventCodeForLength.trim();
    if (trimmedExternalEventCode.length() > 128) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    if (EmptyChecks.isBlank(requestForValidation.getExternalEventId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    String externalEventIdForLength = requestForValidation.getExternalEventId();
    String trimmedExternalEventId = externalEventIdForLength.trim();
    if (trimmedExternalEventId.length() > 256) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    boolean externalEventRequestIdentityIsIncomplete = EmptyChecks.isNull(requestForValidation.getBizDate())
        || EmptyChecks.isBlank(command.idempotencyKey())
        || EmptyChecks.isBlank(command.requestId())
        || EmptyChecks.isBlank(command.traceId());
    if (externalEventRequestIdentityIsIncomplete) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    String eventSource = command.eventSource();
    String normalizedEventSource = eventSource.trim();
    if (!eventSource.equals(normalizedEventSource)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.trigger.external_event_identity_invalid");
    }
    ExternalEventLaunchRequest request = command.request();
    String externalEventCode = request.getExternalEventCode();
    String normalizedExternalEventCode = externalEventCode.trim();
    if (!externalEventCode.equals(normalizedExternalEventCode)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.trigger.external_event_identity_invalid");
    }
    String externalEventId = request.getExternalEventId();
    String normalizedExternalEventId = externalEventId.trim();
    if (!externalEventId.equals(normalizedExternalEventId)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.trigger.external_event_identity_invalid");
    }
  }

  private void assertSameExternalEventPayload(
      ExternalEventDeliveryEntity existing, ExternalEventLaunchRequest incoming) {
    ExternalEventLaunchRequest original;
    try {
      original =
          JsonUtils.fromJsonStrict(existing.getPayloadSnapshot(), ExternalEventLaunchRequest.class);
    } catch (IllegalArgumentException invalidPayload) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
    boolean externalEventRequestConflictsWithExisting =
        !Objects.equals(existing.getEventCode(), incoming.getExternalEventCode())
        || !Objects.equals(existing.getBizDate(), incoming.getBizDate())
        || !Objects.equals(original, incoming);
    if (externalEventRequestConflictsWithExisting) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
  }

  private void registerExternalEventDeliveryAlias(
      ExternalEventDeliveryEntity delivery, String idempotencyKey) {
    externalEventDeliveryMapper.insertAlias(
        delivery.getTenantId(), idempotencyKey, delivery.getId());
    Long resolvedDeliveryId =
        externalEventDeliveryMapper.selectAliasDeliveryId(delivery.getTenantId(), idempotencyKey);
    if (!Objects.equals(delivery.getId(), resolvedDeliveryId)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
  }

  private ExternalEventLaunchResponse externalEventLaunchResponse(
      ExternalEventDeliveryEntity delivery) {
    List<ExternalEventLaunchTarget> targets = externalEventDeliveryMapper
        .selectTargetStatuses(
            delivery.getTenantId(), delivery.getEventSource(), delivery.getEventId())
        .stream()
        .map(target ->
            new ExternalEventLaunchTarget(target.workflowDefinitionId(), target.requestId()))
        .toList();
    return new ExternalEventLaunchResponse(
        delivery.getRequestId(), delivery.getEventId(), delivery.getTraceId(), targets);
  }

  private String externalEventTargetDedupKey(
      String tenantId, String eventSource, String eventId, Long workflowDefinitionId) {
    String identity =
        tenantId + "\u0000" + eventSource + "\u0000" + eventId + "\u0000" + workflowDefinitionId;
    try {
      MessageDigest digestGenerator = MessageDigest.getInstance("SHA-256");
      byte[] identityBytes = identity.getBytes(StandardCharsets.UTF_8);
      byte[] digest = digestGenerator.digest(identityBytes);
      return "event-target:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  @Override
  public ScheduledOccurrenceCatchUpResponse catchUpRejectedOccurrence(
      ScheduledOccurrenceCatchUpCommand command) {
    if (EmptyChecks.isNull(command) || EmptyChecks.isNull(command.request())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    ScheduledOccurrenceCatchUpRequest request = command.request();
    boolean requiredCatchUpFieldsAreMissing = EmptyChecks.isBlank(request.getTenantId())
        || EmptyChecks.isNull(request.getWorkflowDefinitionId())
        || EmptyChecks.isNull(request.getScheduledFireTime())
        || EmptyChecks.isNull(request.getBizDate())
        || EmptyChecks.isBlank(request.getReason())
        || EmptyChecks.isBlank(request.getRequestedBy())
        || EmptyChecks.isBlank(command.idempotencyKey())
        || EmptyChecks.isBlank(command.requestId())
        || EmptyChecks.isBlank(command.traceId());
    if (requiredCatchUpFieldsAreMissing) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    assertTenantActive(request.getTenantId());
    String reason = request.getReason().trim();
    String requestedBy = request.getRequestedBy().trim();
    Instant scheduledFireTime = request.getScheduledFireTime();
    TriggerDescriptor descriptor = triggerDefinitionLoader.loadByWorkflowDefinitionId(
        request.getTenantId(), request.getWorkflowDefinitionId());
    if (EmptyChecks.isNull(descriptor) || !descriptor.isWorkflowEnabled()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.definition_not_found");
    }
    CatchUpPolicyType catchUpPolicy = CatchUpPolicyType.fromCode(descriptor.getCatchUpPolicy());
    if (catchUpPolicy == CatchUpPolicyType.NONE) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.trigger.occurrence_catch_up_disabled");
    }
    String dedupKey = "scheduled-occurrence-catch-up:"
        + request.getWorkflowDefinitionId()
        + ":"
        + scheduledFireTime.toEpochMilli();
    Map<String, Object> params = new LinkedHashMap<>();
    params.put(
        "operationType",
        catchUpPolicy == CatchUpPolicyType.MANUAL_APPROVAL
            ? "SCHEDULED_OCCURRENCE_CATCH_UP_APPROVAL"
            : "SCHEDULED_OCCURRENCE_CATCH_UP");
    String scheduledFireTimeText = scheduledFireTime.toString();
    params.put("scheduledFireTime", scheduledFireTimeText);
    params.put("requestedBy", requestedBy);
    params.put("reason", reason);
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(request.getTenantId())
        .workflowDefinitionId(request.getWorkflowDefinitionId())
        .bizDate(request.getBizDate())
        .triggerType(TriggerType.CATCH_UP)
        .requestId(command.requestId())
        .traceId(command.traceId())
        .params(params)
        .build();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx.execute(ignored -> {
      triggerRequestMapper.acquireBatchDaySettlementFence(
          request.getTenantId(), request.getWorkflowDefinitionId(), null, request.getBizDate());
      String occurrenceStatus = occurrenceRecorder.lockAndReadStatus(
          request.getTenantId(),
          request.getWorkflowDefinitionId(),
          scheduledFireTime,
          request.getBizDate());
      if (ScheduledOccurrenceStatus.REQUESTED.code().equals(occurrenceStatus)
          || ScheduledOccurrenceStatus.MISFIRE_PENDING.code().equals(occurrenceStatus)) {
        String acceptedRequestId = occurrenceRecorder.catchUpRequestId(
            request.getTenantId(), request.getWorkflowDefinitionId(), scheduledFireTime);
        if (EmptyChecks.isNotBlank(acceptedRequestId)) {
          TriggerRequestEntity accepted = triggerRequestMapper.selectByTenantAndRequestId(
              request.getTenantId(), acceptedRequestId);
          return occurrenceCatchUpResponse(accepted.getRequestId(), occurrenceStatus);
        }
      }
      if (!ScheduledOccurrenceStatus.CLOSED_DAY_REJECTED.code().equals(occurrenceStatus)) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.occurrence_not_rejected");
      }
      TriggerRequestEntity existing =
          triggerRequestMapper.selectByTenantAndDedupKey(request.getTenantId(), dedupKey);
      if (EmptyChecks.isNotNull(existing)) {
        return occurrenceCatchUpResponse(existing.getRequestId(), occurrenceStatus);
      }
      TriggerRequestEntity accepted = buildAcceptedEntity(launchRequest, dedupKey);
      triggerRequestMapper.insert(accepted);
      if (catchUpPolicy == CatchUpPolicyType.MANUAL_APPROVAL) {
        TriggerMisfirePendingEntity pending = new TriggerMisfirePendingEntity();
        pending.setTenantId(request.getTenantId());
        pending.setWorkflowDefinitionId(request.getWorkflowDefinitionId());
        pending.setJobCode(descriptor.getJobCode());
        pending.setScheduledFireTime(scheduledFireTime);
        Duration pendingRetention =
            Duration.ofDays(triggerRuntimeProperties.getMisfirePendingRetentionDays());
        Instant pendingExpiresAt = BatchDateTimeSupport.utcNow().plus(pendingRetention);
        pending.setExpiresAt(pendingExpiresAt);
        if (triggerMisfirePendingMapper.insertPending(pending) == 0) {
          pending = triggerMisfirePendingMapper.selectBySchedule(
              request.getTenantId(), request.getWorkflowDefinitionId(), scheduledFireTime);
        }
        Guard.requireFound(pending, "misfire pending occurrence not found");
        triggerMisfirePendingMapper.linkCatchUpRequest(pending.getId(), accepted.getId());
      } else {
        publishLaunchOutbox(launchRequest, dedupKey);
      }
      if (!occurrenceRecorder.acceptRejectedOccurrence(
          command,
          scheduledFireTime,
          requestedBy,
          reason,
          catchUpPolicy == CatchUpPolicyType.MANUAL_APPROVAL
              ? ScheduledOccurrenceStatus.MISFIRE_PENDING
              : ScheduledOccurrenceStatus.REQUESTED)) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.occurrence_not_rejected");
      }
      return occurrenceCatchUpResponse(
          command.requestId(),
          catchUpPolicy == CatchUpPolicyType.MANUAL_APPROVAL
              ? ScheduledOccurrenceStatus.MISFIRE_PENDING.code()
              : ScheduledOccurrenceStatus.REQUESTED.code());
    });
  }

  private ScheduledOccurrenceCatchUpResponse occurrenceCatchUpResponse(
      String requestId, String occurrenceStatus) {
    String status = ScheduledOccurrenceStatus.MISFIRE_PENDING.code().equals(occurrenceStatus)
        ? "PENDING_APPROVAL"
        : "ACCEPTED";
    return new ScheduledOccurrenceCatchUpResponse(requestId, status);
  }

  @Override
  public TriggerLaunchStatus findLaunchStatus(String tenantId, String idempotencyKey) {
    return findLaunchStatus(tenantId, idempotencyKey, null);
  }

  @Override
  public TriggerLaunchStatus findLaunchStatus(
      String tenantId, String idempotencyKey, String externalEventSource) {
    if (EmptyChecks.isBlank(tenantId)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (EmptyChecks.isBlank(idempotencyKey)) {
      throw BizException.of(
          ResultCode.MISSING_IDEMPOTENCY_KEY,
          ResultCode.MISSING_IDEMPOTENCY_KEY.detailKey(),
          ResultCode.MISSING_IDEMPOTENCY_KEY.defaultMessage());
    }
    TriggerLaunchStatus status =
        triggerRequestMapper.selectLaunchStatus(tenantId, idempotencyKey, externalEventSource);
    return Guard.requireFound(status, "launch request not found");
  }

  @Override
  public LaunchResponse launchScheduled(ScheduledTriggerCommand command) {
    assertTenantActive(command.descriptor().getTenantId());
    assertScheduledWorkflowAdmitted(command);
    ScheduledOccurrenceObservation observed = occurrenceRecorder.observed(command.descriptor(), command.fireTime());
    LaunchResponse settled = settledOccurrenceResponse(command, observed);
    if (settled != null) {
      return settled;
    }
    LaunchRequest launchRequest = resolveScheduledRequest(command, observed);
    if (EmptyChecks.isNull(launchRequest.bizDate())) {
      occurrenceRecorder.record(
          command.descriptor(),
          command.fireTime(),
          null,
          ScheduledOccurrenceStatus.SKIPPED_CALENDAR,
          null);
      return skipScheduled(command);
    }
    Optional<Instant> dependencyReadyAt = upstreamReadyAt(command, launchRequest);
    if (dependencyReadyAt.isPresent()) {
      String dependencyReadyAtText = dependencyReadyAt.get().toString();
      Map<String, Object> params = new LinkedHashMap<>(launchRequest.params());
      params.put("dependencyReadyAt", dependencyReadyAtText);
      launchRequest = launchRequest.toBuilder().params(params).build();
    }
    String dedupKey = buildScheduledDedupKey(command);
    try {
      return persistAndForward(launchRequest, dedupKey, command);
    } catch (RuntimeException failure) {
      if (!hasSqlState(failure, CLOSED_BATCH_DAY_SQL_STATE)) {
        throw failure;
      }
      occurrenceRecorder.recordClosedBatchDayRejection(
          command.descriptor(),
          command.fireTime(),
          launchRequest.bizDate(),
          "BATCH_DAY_CLOSED: explicit CATCH_UP or RERUN request required");
      String tenantIdForLog = LogSanitizer.value(command.descriptor().getTenantId());
      String workflowCodeForLog = LogSanitizer.value(command.descriptor().getWorkflowCode());
      log.warn(
          "scheduled fire rejected because its batch day is closed: tenantId={}, workflowCode={},"
              + " bizDate={}, fireTime={}",
          tenantIdForLog,
          workflowCodeForLog,
          launchRequest.bizDate(),
          command.fireTime());
      return LaunchResponse.skipped(command.traceId());
    }
  }

  private LaunchResponse settledOccurrenceResponse(
      ScheduledTriggerCommand command, ScheduledOccurrenceObservation observed) {
    if (observed == null) {
      return null;
    }
    if (ScheduledOccurrenceStatus.REQUESTED.code().equals(observed.status())
        || (ScheduledOccurrenceStatus.MISFIRE_PENDING.code().equals(observed.status())
            && observed.requestId() != null)) {
      TriggerRequestEntity accepted = triggerRequestMapper.selectByTenantAndRequestId(
          command.descriptor().getTenantId(), observed.requestId());
      if (accepted == null
          || !Objects.equals(accepted.getWorkflowDefinitionId(), command.descriptor().getWorkflowDefinitionId())
          || !Objects.equals(accepted.getBizDate(), observed.bizDate())) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.trigger_target_mismatch");
      }
      return new LaunchResponse(accepted.getRequestId(), accepted.getTraceId());
    }
    if (ScheduledOccurrenceStatus.CLOSED_DAY_REJECTED.code().equals(observed.status())
        && command.triggerType() == TriggerType.CATCH_UP) {
      // 已关闭批日保留既有显式补跑合同；普通迟到调度仍不能撤销拒绝。
      return null;
    }
    if (!ScheduledOccurrenceStatus.WAITING_READINESS.code().equals(observed.status())
        && !ScheduledOccurrenceStatus.MISFIRE_PENDING.code().equals(observed.status())) {
      // 普通回调不能撤销终态；批日拒绝后的人工补跑仍走独立审批入口。
      return LaunchResponse.skipped(command.traceId());
    }
    return null;
  }

  private LaunchRequest resolveScheduledRequest(
      ScheduledTriggerCommand command, ScheduledOccurrenceObservation observed) {
    if (observed != null) {
      String calendarCode = CodeNormalizer.toConfigFormOrNull(command.descriptor().getCalendarCode());
      if (!Objects.equals(observed.scheduleVersion(), workflowVersion(command.descriptor()))
          || !Objects.equals(observed.calendarCode(), calendarCode)
          || observed.bizDate() == null) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.scheduled_version_stale");
      }
      return launchAdapterService.fromObservedScheduledTrigger(command, observed.bizDate());
    }
    CalendarBizDateDefinition calendar = calendarDefinitionProvider.load(
        command.descriptor().getTenantId(), command.descriptor().getCalendarCode());
    return launchAdapterService.fromScheduledTrigger(command, calendar);
  }

  private boolean hasSqlState(Throwable failure, String expectedSqlState) {
    for (Throwable current = failure;
        EmptyChecks.isNotNull(current);
        current = current.getCause()) {
      if (current instanceof SQLException sqlException
          && expectedSqlState.equals(sqlException.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  /**
   * ADR-043 依赖感知 fire 闸:所有声明的上游都必须就绪后才 fire。
   *
   * <p>无声明(绝大多数存量触发器)→ 直接放行,行为不变。
   */
  private Optional<Instant> upstreamReadyAt(
      ScheduledTriggerCommand command, LaunchRequest launchRequest) {
    List<String> dependencies = EmptyChecks.isNull(command.descriptor().getDependsOnJobCodes())
        ? List.of()
        : command.descriptor().getDependsOnJobCodes().stream()
            .filter(Texts::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    if (EmptyChecks.isNull(command.descriptor().getDependsOnJobCodes())
        && Texts.hasText(command.descriptor().getDependsOnJobCode())) {
      String dependencyJobCode = command.descriptor().getDependsOnJobCode();
      String trimmedDependencyJobCode = dependencyJobCode.trim();
      dependencies = List.of(trimmedDependencyJobCode);
    }
    if (EmptyChecks.isEmpty(dependencies)) {
      return Optional.empty();
    }
    Instant latestReadyAt = null;
    for (String dependency : dependencies) {
      Optional<Instant> readyAt =
          upstreamReadiness.readyAt(launchRequest.tenantId(), dependency, launchRequest.bizDate());
      if (!readyAt.isPresent()) {
        // ADR-043:未就绪依赖保持当前 fire 的原业务日，交由 Quartz 延迟重检。
        throw new UpstreamNotReadyException(
            launchRequest.tenantId(),
            command.descriptor().getJobCode(),
            dependency,
            launchRequest.bizDate());
      }
      if (EmptyChecks.isNull(latestReadyAt) || readyAt.get().isAfter(latestReadyAt)) {
        latestReadyAt = readyAt.get();
      }
    }
    return Optional.of(latestReadyAt);
  }

  @Override
  @Transactional
  public LaunchResponse createPendingCatchUp(ScheduledTriggerCommand command) {
    assertTenantActive(command.descriptor().getTenantId());
    assertScheduledWorkflowAdmitted(command);
    ScheduledOccurrenceObservation observed = occurrenceRecorder.observed(command.descriptor(), command.fireTime());
    LaunchResponse settled = settledOccurrenceResponse(command, observed);
    if (settled != null) {
      return settled;
    }
    LaunchRequest launchRequest = resolveScheduledRequest(command, observed);
    if (EmptyChecks.isNull(launchRequest.bizDate())) {
      occurrenceRecorder.record(
          command.descriptor(),
          command.fireTime(),
          null,
          ScheduledOccurrenceStatus.SKIPPED_CALENDAR,
          null);
      return skipScheduled(command);
    }
    String dedupKey = buildScheduledDedupKey(command);
    LaunchResponse response = persistPending(launchRequest, dedupKey, command);
    occurrenceRecorder.recordAccepted(
        command, launchRequest.bizDate(), ScheduledOccurrenceStatus.MISFIRE_PENDING, response.instanceNo());
    return response;
  }

  @Override
  public LaunchResponse approvePendingCatchUp(PendingCatchUpApprovalCommand command) {
    validatePendingApproval(command);
    assertTenantActive(command.getTenantId());

    PendingApprovalTarget pendingTarget = resolveRequestFromPending(command);
    TriggerRequestEntity requestFromPending =
        EmptyChecks.isNull(pendingTarget) ? null : pendingTarget.request();

    // 5.6:幂等 —— 若已有相同 key 的请求被 launch 过,则提前返回
    if (EmptyChecks.isNull(requestFromPending)
        && EmptyChecks.isNotBlank(command.getIdempotencyKey())) {
      TriggerRequestEntity existing = triggerRequestMapper.selectByTenantAndDedupKey(
          command.getTenantId(), command.getIdempotencyKey());
      if (EmptyChecks.isNotNull(existing)
          && TriggerRequestStatus.LAUNCHED.code().equalsIgnoreCase(existing.getRequestStatus())) {
        return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
      }
    }

    // ADR-010：审批通过后通过 outbox 异步转发，绝不走同步 HTTP。CAS → INSERT outbox；实际建 Run 后由 Orchestrator 标 LAUNCHED
    // 接纳与发布在同事务内完成，PROCESSING 持续到 Orchestrator 完成实际启动或持久化等待。
    // relay 周期把 trigger_outbox_event 推到 Kafka topic batch.trigger.launch.v1，
    // orchestrator 端消费触发实际 launch；最终一致性由 outbox + (tenant,request_id) 唯一约束保证。
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx.execute(ignored -> {
      TriggerRequestEntity pendingRequest = EmptyChecks.isNotNull(requestFromPending)
          ? triggerRequestMapper.selectById(requestFromPending.getId())
          : triggerRequestMapper.selectByTenantAndRequestId(
              command.getTenantId(), command.getRequestId());
      Guard.requireFound(pendingRequest, "pending catch-up request not found");
      if (!TriggerType.CATCH_UP.code().equalsIgnoreCase(pendingRequest.getTriggerType())) {
        throw BizException.of(ResultCode.BUSINESS_ERROR, "error.request.not_catch_up");
      }
      if (TriggerRequestStatus.REJECTED
          .code()
          .equalsIgnoreCase(pendingRequest.getRequestStatus())) {
        throw BizException.of(ResultCode.BUSINESS_ERROR, "error.request.already_rejected");
      }
      if (TriggerRequestStatus.LAUNCHED
          .code()
          .equalsIgnoreCase(pendingRequest.getRequestStatus())) {
        return new LaunchResponse(pendingRequest.getRequestId(), pendingRequest.getTraceId());
      }
      LaunchRequest original = restorePendingLaunch(pendingRequest);
      // 原子 CAS：ACCEPTED → PROCESSING，并发审批只有一个能进入
      int claimed = triggerRequestMapper.updateRequestStatusConditional(
          command.getTenantId(),
          command.getRequestId(),
          TriggerRequestStatus.PROCESSING.code(),
          TriggerRequestStatus.ACCEPTED.code());
      if (claimed <= 0) {
        // 另一实例正在处理；返回当前状态，重试方需重新查询
        return new LaunchResponse(pendingRequest.getRequestId(), pendingRequest.getTraceId());
      }
      boolean pendingTargetRequiresApproval =
          EmptyChecks.isNotNull(pendingTarget) && pendingTarget.approvePending();
      if (pendingTargetRequiresApproval) {
        int approved =
            triggerMisfirePendingMapper.approve(pendingTarget.pendingId(), "trigger-api");
        if (approved != 1) {
          TriggerMisfirePendingEntity current =
              triggerMisfirePendingMapper.selectById(pendingTarget.pendingId());
          if (EmptyChecks.isNull(current)
              || !MisfirePendingStatus.APPROVED.code().equals(current.getStatus())) {
            throw BizException.of(
                ResultCode.STATE_CONFLICT, "error.trigger.pending_approval_changed");
          }
        }
      }
      Map<String, Object> restoredParams =
          EmptyChecks.isNull(original.params()) ? Map.of() : original.params();
      Map<String, Object> approvalParams = new LinkedHashMap<>(restoredParams);
      approvalParams.put("operationType", "CATCH_UP_APPROVAL");
      approvalParams.put("approvalMode", "MANUAL_APPROVAL");
      approvalParams.put("catchUpApproved", true);
      String approvalReason =
          EmptyChecks.isNull(command.getReason()) ? "" : command.getReason();
      approvalParams.put("reason", approvalReason);
      LaunchRequest launchRequest = original.toBuilder().params(approvalParams).build();
      String dedupKey = Texts.hasText(pendingRequest.getDedupKey())
          ? pendingRequest.getDedupKey()
          : pendingRequest.getRequestId();
      publishLaunchOutbox(launchRequest, dedupKey);
      // 进程可能在恢复状态提交后、事务提交回调触发 DAG
      // 推进前停止。先根据已持久化的成功节点运行记录协调，
      boolean pendingMisfireCanBeApproved = EmptyChecks.isNotNull(pendingTarget)
          && pendingTarget.approvePending()
          && EmptyChecks.isNotNull(pendingTarget.scheduledFireTime());
      if (pendingMisfireCanBeApproved) {
        occurrenceRecorder.transition(
            command.getTenantId(),
            pendingRequest.getWorkflowDefinitionId(),
            pendingTarget.scheduledFireTime(),
            ScheduledOccurrenceStatus.MISFIRE_PENDING,
            ScheduledOccurrenceStatus.REQUESTED,
            pendingRequest.getRequestId());
      }
      return new LaunchResponse(pendingRequest.getRequestId(), pendingRequest.getTraceId());
    });
  }

  private LaunchRequest restorePendingLaunch(TriggerRequestEntity pending) {
    LaunchRequest original;
    try {
      original = EmptyChecks.isBlank(pending.getLaunchPayload())
          ? null
          : JsonUtils.fromJsonStrict(pending.getLaunchPayload(), LaunchRequest.class);
    } catch (IllegalArgumentException invalidPayload) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.pending_snapshot_invalid");
    }
    boolean pendingLaunchSnapshotIsInconsistent = EmptyChecks.isNull(original)
        || !Objects.equals(original.tenantId(), pending.getTenantId())
        || !Objects.equals(original.requestId(), pending.getRequestId())
        || !Objects.equals(original.workflowDefinitionId(), pending.getWorkflowDefinitionId())
        || !Objects.equals(original.jobCode(), pending.getJobCode())
        || !Objects.equals(original.bizDate(), pending.getBizDate())
        || !Objects.equals(original.traceId(), pending.getTraceId())
        || TriggerType.CATCH_UP != original.triggerType()
        || Boolean.TRUE.equals(original.dryRun()) != Boolean.TRUE.equals(pending.getDryRun());
    if (pendingLaunchSnapshotIsInconsistent) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.pending_snapshot_invalid");
    }
    return original;
  }

  private LaunchResponse persistAndForward(LaunchRequest launchRequest, String dedupKey) {
    return persistAndForward(launchRequest, dedupKey, null);
  }

  private LaunchResponse persistAndForward(
      LaunchRequest launchRequest, String dedupKey, ScheduledTriggerCommand scheduledCommand) {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx.execute(
        ignored -> persistAndForwardInTransaction(launchRequest, dedupKey, scheduledCommand));
  }

  private LaunchResponse persistAndForwardInTransaction(
      LaunchRequest launchRequest, String dedupKey, ScheduledTriggerCommand scheduledCommand) {
    externalEventDeliveryMapper.setTenantContext(launchRequest.tenantId());
    triggerRequestMapper.acquireIdempotencyFence(launchRequest.tenantId(), dedupKey);
    Long eventDeliveryId =
        externalEventDeliveryMapper.selectAliasDeliveryId(launchRequest.tenantId(), dedupKey);
    if (EmptyChecks.isNotNull(eventDeliveryId) && eventDeliveryId > 0) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
    if (EmptyChecks.isNotNull(launchRequest.externalEventSource())) {
      triggerRequestMapper.acquireExternalEventIdentityFence(
          launchRequest.tenantId(),
          launchRequest.externalEventSource(),
          launchRequest.externalEventId());
    }
    triggerRequestMapper.acquireBatchDaySettlementFence(
        launchRequest.tenantId(),
        launchRequest.workflowDefinitionId(),
        launchRequest.jobCode(),
        launchRequest.bizDate());
    TriggerRequestEntity existing =
        triggerRequestMapper.selectByTenantAndDedupKey(launchRequest.tenantId(), dedupKey);
    if (EmptyChecks.isNotNull(existing)) {
      assertSameExternalEvent(existing, launchRequest);
      registerExternalEventIdempotencyAlias(existing, launchRequest, dedupKey);
      recordScheduledRequest(launchRequest, scheduledCommand, existing.getRequestId());
      return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
    }
    if (EmptyChecks.isNotNull(launchRequest.externalEventSource())) {
      ExternalEventDeliveryEntity delivery =
          externalEventDeliveryMapper.selectByIdentityForUpdate(
              launchRequest.tenantId(),
              launchRequest.externalEventSource(),
              launchRequest.externalEventId());
      if (EmptyChecks.isNotNull(delivery)
          && !externalEventDeliveryMapper.containsWorkflowTarget(
              launchRequest.tenantId(),
              launchRequest.externalEventSource(),
              launchRequest.externalEventId(),
              launchRequest.workflowDefinitionId())) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
      }
    }
    Boolean admissionLocked = Boolean.TRUE;
    if (EmptyChecks.isNotNull(scheduledCommand)
        && launchRequest.triggerType() == TriggerType.SCHEDULED) {
      int admissionWorkflowVersion = workflowVersion(scheduledCommand.descriptor());
      admissionLocked = triggerRequestMapper.lockScheduledWorkflowAdmission(
          launchRequest.tenantId(),
          launchRequest.workflowDefinitionId(),
          scheduledCommand.fireTime(),
          admissionWorkflowVersion);
    }
    boolean scheduledAdmissionLockIsMissing = EmptyChecks.isNotNull(scheduledCommand)
        && launchRequest.triggerType() == TriggerType.SCHEDULED
        && !Boolean.TRUE.equals(admissionLocked);
    if (scheduledAdmissionLockIsMissing) {
      int currentWorkflowVersion = workflowVersion(scheduledCommand.descriptor());
      if (triggerRequestMapper.isWorkflowVersionCurrent(
          launchRequest.tenantId(),
          launchRequest.workflowDefinitionId(),
          currentWorkflowVersion)) {
        occurrenceRecorder.transition(
            launchRequest.tenantId(),
            launchRequest.workflowDefinitionId(),
            scheduledCommand.fireTime(),
            ScheduledOccurrenceStatus.PLANNED,
            ScheduledOccurrenceStatus.SUPERSEDED,
            null);
      }
      log.info(
          "scheduled fire skipped by current Workflow schedule: tenantId={}, workflowId={}, fireTime={}",
          launchRequest.tenantId(),
          launchRequest.workflowDefinitionId(),
          scheduledCommand.fireTime());
      return LaunchResponse.skipped(launchRequest.traceId());
    }
    // 新请求在同一事务内完成 request + outbox 写入，中间状态对外不可见，直接落 ACCEPTED。
    int inserted = triggerRequestMapper.insert(buildAcceptedEntity(launchRequest, dedupKey));
    if (inserted == 0 && EmptyChecks.isNotNull(launchRequest.externalEventSource())) {
      TriggerRequestEntity eventExisting = triggerRequestMapper.selectByExternalEventIdentity(
          launchRequest.tenantId(),
          launchRequest.workflowDefinitionId(),
          launchRequest.externalEventSource(),
          launchRequest.externalEventId());
      Guard.requireFound(eventExisting, "conflicting external event request not found");
      assertSameExternalEvent(eventExisting, launchRequest);
      registerExternalEventIdempotencyAlias(eventExisting, launchRequest, dedupKey);
      return new LaunchResponse(eventExisting.getRequestId(), eventExisting.getTraceId());
    }
    publishLaunchOutbox(launchRequest, dedupKey);
    recordScheduledRequest(launchRequest, scheduledCommand, launchRequest.requestId());
    return new LaunchResponse(launchRequest.requestId(), launchRequest.traceId());
  }
  private void assertSameExternalEvent(TriggerRequestEntity existing, LaunchRequest incoming) {
    if (EmptyChecks.isNull(incoming.externalEventSource())) {
      return;
    }
    boolean externalEventIdentityMatches =
        incoming.externalEventSource().equals(existing.getExternalEventSource())
            && incoming.externalEventId().equals(existing.getExternalEventId())
            && incoming.externalEventCode().equals(existing.getExternalEventCode())
            && incoming.bizDate().equals(existing.getBizDate());
    if (!externalEventIdentityMatches) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
  }

  private void registerExternalEventIdempotencyAlias(
      TriggerRequestEntity existing, LaunchRequest incoming, String idempotencyKey) {
    if (EmptyChecks.isNull(incoming.externalEventSource())
        || idempotencyKey.equals(existing.getDedupKey())) {
      return;
    }
    triggerRequestMapper.insertIdempotencyAlias(
        existing.getTenantId(), idempotencyKey, existing.getRequestId());
    String aliasedRequestId = triggerRequestMapper.selectIdempotencyAliasRequestId(
        existing.getTenantId(), idempotencyKey);
    if (!existing.getRequestId().equals(aliasedRequestId)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
  }

  private void recordScheduledRequest(
      LaunchRequest launchRequest, ScheduledTriggerCommand command, String requestId) {
    if (EmptyChecks.isNull(command)) {
      return;
    }
    occurrenceRecorder.recordAccepted(
        command, launchRequest.bizDate(), ScheduledOccurrenceStatus.REQUESTED, requestId);
  }

  private int workflowVersion(TriggerDescriptor descriptor) {
    return EmptyChecks.isNull(descriptor.getWorkflowVersion())
        ? 0
        : descriptor.getWorkflowVersion();
  }

  /**
   * 收敛到 {@link TriggerOutboxDomainEventPublisher} 的唯一 trigger_outbox_event 写入入口。
   *
   * <p>之前主路径直接 mapper.insert(buildOutboxEntity),抽象未真正收敛字段语义会持续漂移。现在统一走 publisher.publishLaunch
   * 路径，由 Outbox 写入边界一次性补齐传输层链路上下文。
   */
  private void publishLaunchOutbox(LaunchRequest r, String dedupKey) {
    Instant sourceFireTime =
        (r.triggerType() == TriggerType.SCHEDULED || r.triggerType() == TriggerType.CATCH_UP)
                && EmptyChecks.isNotNull(r.dataIntervalStart())
                && EmptyChecks.isNotNull(r.params())
                && r.dataIntervalStart().toString().equals(r.params().get("scheduledAt"))
            ? r.dataIntervalStart()
            : BatchDateTimeSupport.utcNow();
    LaunchEnvelope envelope = LaunchEnvelope.of(r, dedupKey, sourceFireTime);
    triggerOutboxPublisher.publishLaunch(r.tenantId(), r.requestId(), r.traceId(), envelope);
  }

  private TriggerRequestEntity buildAcceptedEntity(LaunchRequest r, String dedupKey) {
    TriggerRequestEntity entity = new TriggerRequestEntity();
    entity.setTenantId(r.tenantId());
    entity.setRequestId(r.requestId());
    entity.setTriggerType(r.triggerType().code());
    entity.setJobCode(r.jobCode());
    entity.setWorkflowDefinitionId(r.workflowDefinitionId());
    entity.setBizDate(r.bizDate());
    entity.setDedupKey(dedupKey);
    entity.setRequestStatus("ACCEPTED");
    entity.setTraceId(r.traceId());
    entity.setDryRun(r.dryRun());
    String launchPayload = JsonUtils.toJson(r);
    entity.setLaunchPayload(launchPayload);
    entity.setExternalEventCode(r.externalEventCode());
    entity.setExternalEventSource(r.externalEventSource());
    entity.setExternalEventId(r.externalEventId());
    return entity;
  }

  /** MANUAL_APPROVAL 场景先把 catch-up 请求登记为待审批，不立即转给 orchestrator。 */
  private LaunchResponse persistPending(
      LaunchRequest launchRequest, String dedupKey, ScheduledTriggerCommand command) {
    triggerRequestMapper.acquireBatchDaySettlementFence(
        launchRequest.tenantId(),
        launchRequest.workflowDefinitionId(),
        launchRequest.jobCode(),
        launchRequest.bizDate());
    TriggerRequestEntity existing =
        triggerRequestMapper.selectByTenantAndDedupKey(launchRequest.tenantId(), dedupKey);
    if (EmptyChecks.isNotNull(existing)) {
      linkMisfirePending(command, existing);
      return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
    }
    TriggerRequestEntity entity = buildAcceptedEntity(
        launchRequest.toBuilder().triggerType(TriggerType.CATCH_UP).build(), dedupKey);
    triggerRequestMapper.insert(entity);
    linkMisfirePending(command, entity);
    return new LaunchResponse(entity.getRequestId(), entity.getTraceId());
  }

  private void linkMisfirePending(ScheduledTriggerCommand command, TriggerRequestEntity request) {
    if (EmptyChecks.isNull(command) || EmptyChecks.isNull(request)) {
      return;
    }
    TriggerMisfirePendingEntity pending = new TriggerMisfirePendingEntity();
    pending.setTenantId(command.descriptor().getTenantId());
    pending.setWorkflowDefinitionId(command.descriptor().getWorkflowDefinitionId());
    pending.setJobCode(command.descriptor().getJobCode());
    pending.setScheduledFireTime(command.fireTime());
    Instant now = BatchDateTimeSupport.utcNow();
    int retentionDays = triggerRuntimeProperties.getMisfirePendingRetentionDays();
    Duration retentionWindow = Duration.ofDays(retentionDays);
    pending.setExpiresAt(now.plus(retentionWindow));
    if (triggerMisfirePendingMapper.insertPending(pending) == 0) {
      pending = triggerMisfirePendingMapper.selectBySchedule(
          command.descriptor().getTenantId(),
          command.descriptor().getWorkflowDefinitionId(),
          command.fireTime());
    }
    boolean canAssociateCatchUpRequest = EmptyChecks.isNotNull(pending)
        && EmptyChecks.isNotNull(pending.getId())
        && EmptyChecks.isNotNull(request.getId());
    if (canAssociateCatchUpRequest) {
      triggerMisfirePendingMapper.linkCatchUpRequest(pending.getId(), request.getId());
    }
  }

  private PendingApprovalTarget resolveRequestFromPending(PendingCatchUpApprovalCommand command) {
    TriggerMisfirePendingEntity pending;
    if (EmptyChecks.isNull(command.getPendingId())) {
      pending = triggerMisfirePendingMapper.selectByCatchUpRequest(
          command.getTenantId(), command.getRequestId());
      // BatchDay 审批没有 Quartz 待办；有待办时必须在本次事务内完成原义务审批。
      if (EmptyChecks.isNull(pending)) {
        return null;
      }
    } else {
      Long requestedPendingId = command.getPendingId();
      TriggerMisfirePendingEntity storedPending =
          triggerMisfirePendingMapper.selectById(requestedPendingId);
      pending = Guard.requireFound(storedPending, "misfire pending not found");
    }
    if (!command.getTenantId().equals(pending.getTenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (MisfirePendingStatus.REJECTED.code().equalsIgnoreCase(pending.getStatus())
        || MisfirePendingStatus.EXPIRED.code().equalsIgnoreCase(pending.getStatus())) {
      throw BizException.of(ResultCode.BUSINESS_ERROR, "error.request.already_rejected");
    }
    Long requestId = pending.getCatchUpRequestId();
    if (EmptyChecks.isNull(requestId)) {
      throw BizException.of(
          ResultCode.BUSINESS_ERROR,
          ResultCode.BUSINESS_ERROR.detailKey(),
          "misfire pending is not linked to catch-up request");
    }
    TriggerRequestEntity storedRequest = triggerRequestMapper.selectById(requestId);
    TriggerRequestEntity request = Guard.requireFound(storedRequest, "catch-up request not found");
    command.setRequestId(request.getRequestId());
    boolean approvalPending =
        MisfirePendingStatus.PENDING.code().equalsIgnoreCase(pending.getStatus());
    return new PendingApprovalTarget(
        request, pending.getId(), approvalPending, pending.getScheduledFireTime());
  }

  private String buildScheduledDedupKey(ScheduledTriggerCommand command) {
    // R-arch-audit-2026-05-23 P2: 用 Instant.toEpochMilli() 替代 Instant.toString()。
    // Instant.toString() 输出会按精度自动调整（如 "...:00Z" vs "...:00.000Z"），不同 JVM /
    // 序列化路径可能产生不同字符串，导致同一 fireTime 算出不同 dedupKey，去重失效。
    // toEpochMilli() 是稳定的 long → String，跨 JVM 一致。
    String target = EmptyChecks.isNull(command.descriptor().getWorkflowDefinitionId())
        ? "job:" + command.descriptor().getJobCode()
        : "workflow:" + command.descriptor().getWorkflowDefinitionId();
    return command.descriptor().getTenantId()
        + ":"
        + target
        + ":"
        + command.fireTime().toEpochMilli();
  }

  private LaunchResponse skipScheduled(ScheduledTriggerCommand command) {
    String tenantIdForLog = LogSanitizer.value(command.descriptor().getTenantId());
    String jobCodeForLog = LogSanitizer.value(command.descriptor().getJobCode());
    String calendarCodeForLog = LogSanitizer.value(command.descriptor().getCalendarCode());
    log.info(
        "scheduled trigger skipped by business calendar: tenantId={}, jobCode={},"
            + " calendarCode={}, fireTime={}",
        tenantIdForLog,
        jobCodeForLog,
        calendarCodeForLog,
        command.fireTime());
    return LaunchResponse.skipped(command.traceId());
  }

  private void assertScheduledWorkflowAdmitted(ScheduledTriggerCommand command) {
    if (triggerRequestMapper.existsEnabledWorkflowDefinition(
        command.descriptor().getTenantId(), command.descriptor().getWorkflowDefinitionId())) {
      return;
    }
    // 再判断过期根运行是否应进入终态；派发守卫会安全处理已活动节点。
    boolean pendingMisfireRecoveryIsBeingHandled =
        command.triggerType() == TriggerType.CATCH_UP
            && occurrenceRecorder.isPendingMisfireRecovery(
                command.descriptor(), command.fireTime());
    if (pendingMisfireRecoveryIsBeingHandled) {
      return;
    }
    throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.definition_not_found");
  }

  private void assertTenantActive(String tenantId) {
    String status = tenantStatusMapper.selectStatus(tenantId);
    if ("SUSPENDED".equals(status)) {
      // R-arch-audit-2026-05-23 P1: 用 ResultCode.TENANT_SUSPENDED 替代 BUSINESS_ERROR + 字符串后缀，
      // 让 QuartzLaunchJob 能通过 getCode() 枚举比较识别租户暂停语义，
      // 不再依赖脆弱的 e.getMessage().contains("tenant is suspended")。
      throw BizException.of(
          ResultCode.TENANT_SUSPENDED,
          "error.trigger.tenant_suspended",
          tenantId);
    }
  }

  private void validateRequest(TriggerLaunchCommand command) {
    Guard.require(EmptyChecks.isNotNull(command), "launch command is required");
    if (EmptyChecks.isBlank(command.idempotencyKey())) {
      throw BizException.of(
          ResultCode.MISSING_IDEMPOTENCY_KEY,
          "error.common.missing_idempotency_key");
    }
    if (EmptyChecks.isNull(command.request())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    if (EmptyChecks.isBlank(command.request().getTenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    Map<String, Object> params = command.request().getParams();
    boolean containsForbiddenParentWorkflowContext =
        EmptyChecks.isNotNull(params)
            && (params.containsKey("_parentWorkflowRunId") || params.containsKey("_parentNodeCode"));
    if (containsForbiddenParentWorkflowContext) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.trigger.parent_context_forbidden");
    }
    boolean hasWorkflowTarget = EmptyChecks.isNotNull(command.request().getWorkflowDefinitionId());
    if (!hasWorkflowTarget) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.trigger.workflow_target_required");
    }
    if (hasWorkflowTarget && command.request().getWorkflowDefinitionId() <= 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    if (!triggerRequestMapper.existsEnabledWorkflowDefinition(
        command.request().getTenantId(), command.request().getWorkflowDefinitionId())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.definition_not_found");
    }
    if (EmptyChecks.isNull(command.request().getBizDate())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.biz_date_required");
    }
    if (EmptyChecks.isNull(command.request().getTriggerType())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.trigger.type_required");
    }
    String externalEventCode = command.request().getExternalEventCode();
    boolean externalEventCodeIsInvalidForTrigger = EmptyChecks.isNotNull(externalEventCode)
        && (!Texts.hasText(externalEventCode)
            || externalEventCode.trim().length() > 128
            || command.request().getTriggerType() != TriggerType.EVENT);
    if (externalEventCodeIsInvalidForTrigger) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.trigger.external_event_code_invalid");
    }
    String eventSource = command.request().getExternalEventSource();
    String eventId = command.request().getExternalEventId();
    boolean externalEventIdentityWasProvided =
        EmptyChecks.isNotNull(externalEventCode)
            || EmptyChecks.isNotNull(eventSource)
            || EmptyChecks.isNotNull(eventId);
    boolean providedExternalEventIdentityIsInvalid =
        externalEventIdentityWasProvided
            && (!Texts.hasText(eventSource)
                || eventSource.trim().length() > 128
                || !eventSource.equals(eventSource.trim())
                || !Texts.hasText(eventId)
                || eventId.trim().length() > 256
                || !eventId.equals(eventId.trim())
                || command.request().getTriggerType() != TriggerType.EVENT
                || !Texts.hasText(externalEventCode));
    if (providedExternalEventIdentityIsInvalid) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.trigger.external_event_identity_invalid");
    }
  }

  private void validatePendingApproval(PendingCatchUpApprovalCommand command) {
    Guard.require(EmptyChecks.isNotNull(command), "approval command is required");
    if (EmptyChecks.isBlank(command.getTenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    boolean approvalTargetIsMissing =
        EmptyChecks.isNull(command.getPendingId()) && EmptyChecks.isBlank(command.getRequestId());
    if (approvalTargetIsMissing) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.request_id_required");
    }
  }
}
