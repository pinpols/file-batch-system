package io.github.pinpols.batch.trigger.service;

import static io.github.pinpols.batch.trigger.service.TriggerAdmissionRules.CLOSED_BATCH_DAY_SQL_STATE;
import static io.github.pinpols.batch.trigger.service.TriggerAdmissionRules.REQUEST_BODY_REQUIRED;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.dto.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.jdbc.JdbcExceptionSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchResponse;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchStatus;
import io.github.pinpols.batch.trigger.domain.TriggerLaunchStatus;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchCommand;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledOccurrenceCatchUpCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TriggerService 的统一门面，保留 API 手工启动校验并委托独立用例协作者。
 *
 * <p>普通请求经 TriggerRequestPersistence 的原子 request/Outbox 事务；事件接纳保持多目标同事务；
 * 定时补跑保留原 fire、CAS 和冻结快照。待审批创建的声明式事务仍从本门面入口开始。
 */
@Service
@RequiredArgsConstructor
public class DefaultTriggerService implements TriggerService {

  private final LaunchAdapterService launchAdapterService;
  private final TriggerRequestMapper triggerRequestMapper;
  private final TriggerAdmissionRules admissionRules;
  private final TriggerRequestPersistence requestPersistence;
  private final TriggerExternalEventService externalEventService;
  private final TriggerScheduledRecoveryService scheduledRecoveryService;
  private final TriggerLaunchQueryService launchQueryService;

  @Override
  public LaunchResponse launch(TriggerLaunchCommand command) {
    validateRequest(command);
    admissionRules.assertTenantActive(command.request().tenantId());
    LaunchRequest launchRequest = launchAdapterService.fromApiRequest(command);
    try {
      return requestPersistence.persistAndForward(launchRequest, command.idempotencyKey());
    } catch (RuntimeException failure) {
      if (!JdbcExceptionSupport.hasSqlState(failure, CLOSED_BATCH_DAY_SQL_STATE)) {
        throw failure;
      }
      // 按租户恢复名额等待，不暂停 Kafka 分区，也不更改原业务日期。
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.trigger.batch_day_closed", launchRequest.bizDate());
    }
  }

  @Override
  public LaunchResponse verifyCatchUpSubmission(TriggerLaunchCommand command) {
    if (EmptyChecks.isNull(command)
        || EmptyChecks.isNull(command.request())
        || EmptyChecks.isBlank(command.idempotencyKey())
        || EmptyChecks.isBlank(command.request().tenantId())
        || EmptyChecks.isNull(command.request().workflowDefinitionId())
        || command.request().workflowDefinitionId() <= 0
        || EmptyChecks.isNull(command.request().bizDate())
        || command.request().triggerType() != TriggerType.CATCH_UP) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    return launchQueryService.verifyCatchUpSubmission(
        launchAdapterService.fromApiRequest(command), command.idempotencyKey());
  }

  @Override
  public ExternalEventLaunchResponse launchExternalEvent(ExternalEventLaunchCommand command) {
    return externalEventService.launchExternalEvent(command);
  }

  @Override
  public ExternalEventLaunchStatus findExternalEventStatus(
      String tenantId, String eventSource, String idempotencyKey) {
    return launchQueryService.findExternalEventStatus(tenantId, eventSource, idempotencyKey);
  }

  @Override
  public TriggerLaunchStatus findLaunchStatus(String tenantId, String idempotencyKey) {
    return launchQueryService.findLaunchStatus(tenantId, idempotencyKey);
  }

  @Override
  public TriggerLaunchStatus findLaunchStatus(
      String tenantId, String idempotencyKey, String externalEventSource) {
    return launchQueryService.findLaunchStatus(tenantId, idempotencyKey, externalEventSource);
  }

  @Override
  public LaunchResponse launchScheduled(ScheduledTriggerCommand command) {
    return scheduledRecoveryService.launchScheduled(command);
  }

  @Override
  @Transactional
  public LaunchResponse createPendingCatchUp(ScheduledTriggerCommand command) {
    return scheduledRecoveryService.createPendingCatchUp(command);
  }

  @Override
  public LaunchResponse approvePendingCatchUp(PendingCatchUpApprovalCommand command) {
    return scheduledRecoveryService.approvePendingCatchUp(command);
  }

  @Override
  public ScheduledOccurrenceCatchUpResponse catchUpRejectedOccurrence(
      ScheduledOccurrenceCatchUpCommand command) {
    return scheduledRecoveryService.catchUpRejectedOccurrence(command);
  }

  private void validateRequest(TriggerLaunchCommand command) {
    Guard.require(EmptyChecks.isNotNull(command), "launch command is required");
    if (EmptyChecks.isBlank(command.idempotencyKey())) {
      throw BizException.of(
          ResultCode.MISSING_IDEMPOTENCY_KEY, "error.common.missing_idempotency_key");
    }
    if (EmptyChecks.isNull(command.request())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    if (EmptyChecks.isBlank(command.request().tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    Map<String, Object> params = command.request().params();
    boolean containsForbiddenParentWorkflowContext = EmptyChecks.isNotNull(params)
        && (params.containsKey("_parentWorkflowRunId") || params.containsKey("_parentNodeCode"));
    if (containsForbiddenParentWorkflowContext) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.trigger.parent_context_forbidden");
    }
    boolean hasWorkflowTarget = EmptyChecks.isNotNull(command.request().workflowDefinitionId());
    if (!hasWorkflowTarget) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.trigger.workflow_target_required");
    }
    if (hasWorkflowTarget && command.request().workflowDefinitionId() <= 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    if (!triggerRequestMapper.existsEnabledWorkflowDefinition(
        command.request().tenantId(), command.request().workflowDefinitionId())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.definition_not_found");
    }
    if (EmptyChecks.isNull(command.request().bizDate())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.biz_date_required");
    }
    if (EmptyChecks.isNull(command.request().triggerType())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.trigger.type_required");
    }
    String externalEventCode = command.request().externalEventCode();
    boolean externalEventCodeIsInvalidForTrigger = EmptyChecks.isNotNull(externalEventCode)
        && (!Texts.hasText(externalEventCode)
            || externalEventCode.trim().length() > 128
            || command.request().triggerType() != TriggerType.EVENT);
    if (externalEventCodeIsInvalidForTrigger) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.trigger.external_event_code_invalid");
    }
    String eventSource = command.request().externalEventSource();
    String eventId = command.request().externalEventId();
    boolean externalEventIdentityWasProvided = EmptyChecks.isNotNull(externalEventCode)
        || EmptyChecks.isNotNull(eventSource)
        || EmptyChecks.isNotNull(eventId);
    boolean providedExternalEventIdentityIsInvalid = externalEventIdentityWasProvided
        && (!Texts.hasText(eventSource)
            || eventSource.trim().length() > 128
            || !eventSource.equals(eventSource.trim())
            || !Texts.hasText(eventId)
            || eventId.trim().length() > 256
            || !eventId.equals(eventId.trim())
            || command.request().triggerType() != TriggerType.EVENT
            || !Texts.hasText(externalEventCode));
    if (providedExternalEventIdentityIsInvalid) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.trigger.external_event_identity_invalid");
    }
  }
}
