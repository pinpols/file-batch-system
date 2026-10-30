package io.github.pinpols.batch.trigger.service;

import static io.github.pinpols.batch.trigger.service.TriggerAdmissionRules.REQUEST_BODY_REQUIRED;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchStatus;
import io.github.pinpols.batch.trigger.domain.ExternalEventTargetStatus;
import io.github.pinpols.batch.trigger.domain.TriggerLaunchStatus;
import io.github.pinpols.batch.trigger.domain.entity.ExternalEventDeliveryEntity;
import io.github.pinpols.batch.trigger.mapper.ExternalEventDeliveryMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 按原租户和幂等身份回查启动请求及外部事件投递状态。
 *
 * <p>保留原查询事务及 RLS 绑定；查询不执行新请求接纳或状态推进。 */
@Component
@RequiredArgsConstructor
final class TriggerLaunchQueryService {

  private final TriggerRequestMapper triggerRequestMapper;
  private final ExternalEventDeliveryMapper externalEventDeliveryMapper;
  private final PlatformTransactionManager transactionManager;

  ExternalEventLaunchStatus findExternalEventStatus(
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
    List<ExternalEventTargetStatus> targets = externalEventDeliveryMapper.selectTargetStatuses(
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

  /** 原租户与请求键下只读核验，不检查当前 Workflow 配置，也不接纳新请求。 */
  LaunchResponse verifyCatchUpSubmission(LaunchRequest expected, String idempotencyKey) {
    return RlsTenantContextHolder.runWithTenant(expected.tenantId(), () -> {
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      transaction.setReadOnly(true);
      return transaction.execute(ignored -> {
        externalEventDeliveryMapper.setTenantContext(expected.tenantId());
        TriggerRequestEntity existing =
            triggerRequestMapper.selectByTenantAndDedupKey(expected.tenantId(), idempotencyKey);
        Guard.requireFound(existing, "launch request not found");
        TriggerCatchUpIdentity.requireMatchingIfCatchUp(existing, expected);
        return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
      });
    });
  }

  TriggerLaunchStatus findLaunchStatus(String tenantId, String idempotencyKey) {
    return findLaunchStatus(tenantId, idempotencyKey, null);
  }

  TriggerLaunchStatus findLaunchStatus(
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
}
