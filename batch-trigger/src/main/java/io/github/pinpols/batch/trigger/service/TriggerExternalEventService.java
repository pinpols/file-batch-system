package io.github.pinpols.batch.trigger.service;

import static io.github.pinpols.batch.trigger.service.TriggerAdmissionRules.CLOSED_BATCH_DAY_SQL_STATE;
import static io.github.pinpols.batch.trigger.service.TriggerAdmissionRules.EXTERNAL_EVENT_IDENTITY_CONFLICT;
import static io.github.pinpols.batch.trigger.service.TriggerAdmissionRules.REQUEST_BODY_REQUIRED;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.jdbc.JdbcExceptionSupport;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchResponse;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchTarget;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchCommand;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchInput;
import io.github.pinpols.batch.trigger.domain.entity.ExternalEventDeliveryEntity;
import io.github.pinpols.batch.trigger.mapper.ExternalEventDeliveryMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 外部事件的身份核验、目标冻结和多目标原子接纳。
 *
 * <p>整个投递沿用同一接纳事务；逐目标写入只调用持久化辅助方法，不拆成多个 REQUIRES_NEW。 */
@Component
@RequiredArgsConstructor
final class TriggerExternalEventService {

  private final TriggerRequestMapper triggerRequestMapper;
  private final ExternalEventDeliveryMapper externalEventDeliveryMapper;
  private final PlatformTransactionManager transactionManager;
  private final TriggerAdmissionRules admissionRules;
  private final TriggerRequestPersistence requestPersistence;

  ExternalEventLaunchResponse launchExternalEvent(ExternalEventLaunchCommand command) {
    validateExternalEventCommand(command);
    ExternalEventLaunchInput request = command.request();
    admissionRules.assertTenantActive(request.tenantId());
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    TransactionCallback<ExternalEventLaunchResponse> admissionTransaction =
        ignored -> admitExternalEvent(command);
    try {
      return RlsTenantContextHolder.runWithTenant(
          request.tenantId(), () -> tx.execute(admissionTransaction));
    } catch (RuntimeException failure) {
      if (!JdbcExceptionSupport.hasSqlState(failure, CLOSED_BATCH_DAY_SQL_STATE)) {
        throw failure;
      }
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.trigger.batch_day_closed", request.bizDate());
    }
  }

  private ExternalEventLaunchResponse admitExternalEvent(ExternalEventLaunchCommand command) {
    ExternalEventLaunchInput request = command.request();
    externalEventDeliveryMapper.setTenantContext(request.tenantId());
    triggerRequestMapper.acquireIdempotencyFence(request.tenantId(), command.idempotencyKey());
    TriggerRequestEntity existingRequest = triggerRequestMapper.selectByTenantAndDedupKey(
        request.tenantId(), command.idempotencyKey());
    if (EmptyChecks.isNotNull(existingRequest)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
    triggerRequestMapper.acquireExternalEventIdentityFence(
        request.tenantId(), command.eventSource(), request.externalEventId());
    ExternalEventDeliveryEntity existing = externalEventDeliveryMapper.selectByIdentityForUpdate(
        request.tenantId(), command.eventSource(), request.externalEventId());
    if (EmptyChecks.isNotNull(existing)) {
      assertSameExternalEventPayload(existing, request);
      registerExternalEventDeliveryAlias(existing, command.idempotencyKey());
      return externalEventLaunchResponse(existing);
    }

    List<Long> targets = triggerRequestMapper.selectEnabledExternalEventWorkflowTargets(
        request.tenantId(), request.externalEventCode());
    if (EmptyChecks.isEmpty(targets)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.trigger.external_event_target_not_found");
    }
    List<Long> existingWorkflowTargets =
        triggerRequestMapper.selectWorkflowTargetsByExternalEventIdentity(
            request.tenantId(), command.eventSource(), request.externalEventId());
    if (EmptyChecks.isNotEmpty(existingWorkflowTargets)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
    for (Long workflowDefinitionId : targets) {
      triggerRequestMapper.acquireBatchDaySettlementFence(
          request.tenantId(), workflowDefinitionId, null, request.bizDate());
    }

    ExternalEventDeliveryEntity delivery = new ExternalEventDeliveryEntity();
    delivery.setTenantId(request.tenantId());
    delivery.setEventSource(command.eventSource());
    delivery.setEventId(request.externalEventId());
    delivery.setEventCode(request.externalEventCode());
    delivery.setBizDate(request.bizDate());
    delivery.setRequestId(command.requestId());
    String payloadSnapshot = JsonUtils.toJson(request);
    delivery.setPayloadSnapshot(payloadSnapshot);
    String targetWorkflowDefinitionIds = JsonUtils.toJson(targets);
    delivery.setTargetWorkflowDefinitionIds(targetWorkflowDefinitionIds);
    delivery.setTraceId(command.traceId());
    if (externalEventDeliveryMapper.insert(delivery) == 0) {
      delivery = externalEventDeliveryMapper.selectByIdentityForUpdate(
          request.tenantId(), command.eventSource(), request.externalEventId());
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
          request.tenantId(),
          command.eventSource(),
          request.externalEventId(),
          workflowDefinitionId);
      TriggerRequestEntity acceptedRequest =
          requestPersistence.buildAcceptedEntity(launchRequest, dedupKey);
      int inserted = triggerRequestMapper.insert(acceptedRequest);
      if (inserted == 0) {
        throw BizException.of(ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
      }
      requestPersistence.publishLaunchOutbox(launchRequest, dedupKey);
    }
    return externalEventLaunchResponse(delivery);
  }

  private LaunchRequest createExternalEventLaunchRequest(
      ExternalEventLaunchCommand command, Long workflowDefinitionId, String requestId) {
    ExternalEventLaunchInput request = command.request();
    return LaunchRequest.builder()
        .tenantId(request.tenantId())
        .workflowDefinitionId(workflowDefinitionId)
        .bizDate(request.bizDate())
        .triggerType(TriggerType.EVENT)
        .requestId(requestId)
        .traceId(command.traceId())
        .params(request.params())
        .dataIntervalStart(request.dataIntervalStart())
        .dataIntervalEnd(request.dataIntervalEnd())
        .dryRun(Boolean.TRUE.equals(request.dryRun()))
        .externalEventCode(request.externalEventCode())
        .externalEventSource(command.eventSource())
        .externalEventId(request.externalEventId())
        .build();
  }

  private void validateExternalEventCommand(ExternalEventLaunchCommand command) {
    if (EmptyChecks.isNull(command)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    ExternalEventLaunchInput request = command.request();
    if (EmptyChecks.isNull(request) || EmptyChecks.isBlank(request.tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    String eventSource = command.eventSource();
    if (EmptyChecks.isBlank(eventSource)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    String trimmedEventSource = eventSource.trim();
    if (trimmedEventSource.length() > 128) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    String externalEventCode = request.externalEventCode();
    if (EmptyChecks.isBlank(externalEventCode)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    String trimmedExternalEventCode = externalEventCode.trim();
    if (trimmedExternalEventCode.length() > 128) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    String externalEventId = request.externalEventId();
    if (EmptyChecks.isBlank(externalEventId)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    String trimmedExternalEventId = externalEventId.trim();
    if (trimmedExternalEventId.length() > 256) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    boolean externalEventRequestIdentityIsIncomplete = EmptyChecks.isNull(request.bizDate())
        || EmptyChecks.isBlank(command.idempotencyKey())
        || EmptyChecks.isBlank(command.requestId())
        || EmptyChecks.isBlank(command.traceId());
    if (externalEventRequestIdentityIsIncomplete) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    if (!eventSource.equals(trimmedEventSource)
        || !externalEventCode.equals(trimmedExternalEventCode)
        || !externalEventId.equals(trimmedExternalEventId)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.trigger.external_event_identity_invalid");
    }
  }

  private void assertSameExternalEventPayload(
      ExternalEventDeliveryEntity existing, ExternalEventLaunchInput incoming) {
    ExternalEventLaunchInput original;
    try {
      original =
          JsonUtils.fromJsonStrict(existing.getPayloadSnapshot(), ExternalEventLaunchInput.class);
    } catch (IllegalArgumentException invalidPayload) {
      throw BizException.of(ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
    boolean externalEventRequestConflictsWithExisting =
        !Objects.equals(existing.getEventCode(), incoming.externalEventCode())
            || !Objects.equals(existing.getBizDate(), incoming.bizDate())
            || !Objects.equals(original, incoming);
    if (externalEventRequestConflictsWithExisting) {
      throw BizException.of(ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
    }
  }

  private void registerExternalEventDeliveryAlias(
      ExternalEventDeliveryEntity delivery, String idempotencyKey) {
    externalEventDeliveryMapper.insertAlias(
        delivery.getTenantId(), idempotencyKey, delivery.getId());
    Long resolvedDeliveryId =
        externalEventDeliveryMapper.selectAliasDeliveryId(delivery.getTenantId(), idempotencyKey);
    if (!Objects.equals(delivery.getId(), resolvedDeliveryId)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
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
}
