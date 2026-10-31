package io.github.pinpols.batch.trigger.service;

import static io.github.pinpols.batch.trigger.service.TriggerAdmissionRules.EXTERNAL_EVENT_IDENTITY_CONFLICT;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.domain.entity.ExternalEventDeliveryEntity;
import io.github.pinpols.batch.trigger.event.TriggerOutboxDomainEventPublisher;
import io.github.pinpols.batch.trigger.mapper.ExternalEventDeliveryMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 普通请求的幂等、业务日围栏与 request/Outbox 原子持久化。
 *
 * <p>persistAndForward 自行使用原有 REQUIRES_NEW；构造及发布辅助方法不新开事务，供事件多目标和审批在各自事务中复用。 */
@Component
@RequiredArgsConstructor
@Slf4j
final class TriggerRequestPersistence {

  private final TriggerRequestMapper triggerRequestMapper;
  private final ExternalEventDeliveryMapper externalEventDeliveryMapper;
  private final TriggerOutboxDomainEventPublisher triggerOutboxPublisher;
  private final ScheduledOccurrenceRecorder occurrenceRecorder;
  private final PlatformTransactionManager transactionManager;
  private final TriggerAdmissionRules admissionRules;

  LaunchResponse persistAndForward(LaunchRequest launchRequest, String dedupKey) {
    return persistAndForward(launchRequest, dedupKey, null);
  }

  LaunchResponse persistAndForward(
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
      throw BizException.of(ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
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
      TriggerCatchUpIdentity.requireMatchingIfCatchUp(existing, launchRequest);
      assertSameExternalEvent(existing, launchRequest);
      registerExternalEventIdempotencyAlias(existing, launchRequest, dedupKey);
      recordScheduledRequest(launchRequest, scheduledCommand, existing.getRequestId());
      return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
    }
    if (EmptyChecks.isNotNull(launchRequest.externalEventSource())) {
      ExternalEventDeliveryEntity delivery = externalEventDeliveryMapper.selectByIdentityForUpdate(
          launchRequest.tenantId(),
          launchRequest.externalEventSource(),
          launchRequest.externalEventId());
      if (EmptyChecks.isNotNull(delivery)
          && !externalEventDeliveryMapper.containsWorkflowTarget(
              launchRequest.tenantId(),
              launchRequest.externalEventSource(),
              launchRequest.externalEventId(),
              launchRequest.workflowDefinitionId())) {
        throw BizException.of(ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
      }
    }
    Boolean admissionLocked = Boolean.TRUE;
    if (EmptyChecks.isNotNull(scheduledCommand)
        && launchRequest.triggerType() == TriggerType.SCHEDULED) {
      int admissionWorkflowVersion = admissionRules.workflowVersion(scheduledCommand.descriptor());
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
      int currentWorkflowVersion = admissionRules.workflowVersion(scheduledCommand.descriptor());
      if (triggerRequestMapper.isWorkflowVersionCurrent(
          launchRequest.tenantId(), launchRequest.workflowDefinitionId(), currentWorkflowVersion)) {
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
          LogSanitizer.value(launchRequest.tenantId()),
          LogSanitizer.value(launchRequest.workflowDefinitionId()),
          LogSanitizer.value(scheduledCommand.fireTime()));
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
      throw BizException.of(ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
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
      throw BizException.of(ResultCode.STATE_CONFLICT, EXTERNAL_EVENT_IDENTITY_CONFLICT);
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

  /**
   * 收敛到 {@link TriggerOutboxDomainEventPublisher} 的唯一 trigger_outbox_event 写入入口。
   *
   * <p>之前主路径直接 mapper.insert(buildOutboxEntity),抽象未真正收敛字段语义会持续漂移。现在统一走 publisher.publishLaunch
   * 路径，由 Outbox 写入边界一次性补齐传输层链路上下文。
   */
  void publishLaunchOutbox(LaunchRequest r, String dedupKey) {
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

  TriggerRequestEntity buildAcceptedEntity(LaunchRequest r, String dedupKey) {
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
}
