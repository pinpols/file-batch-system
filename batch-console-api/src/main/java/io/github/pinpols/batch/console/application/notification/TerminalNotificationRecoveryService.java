package io.github.pinpols.batch.console.application.notification;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.audit.mapper.OperationAuditMapper;
import io.github.pinpols.batch.console.domain.audit.support.OperationAuditEvent;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.TerminalDeliveryRetryRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.TerminalDeliveryRecoveryResponse;
import io.github.pinpols.batch.console.domain.notification.mapper.TerminalNotificationRecoveryMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 原投递与人工核对审计原子提交,请求身份跨进程持久去重。 */
@Service
public class TerminalNotificationRecoveryService {
  private record RecoveryAuditEvidence(
      String reason,
      String reconciliationReference,
      int previousAttempt,
      boolean confirmedNotDelivered,
      String terminalEventKey) {}

  public enum Source {
    WEBHOOK,
    RULE,
    PUSH
  }

  private final TerminalNotificationRecoveryMapper mapper;
  private final OperationAuditMapper audit;
  private final TenantIdResolver tenants;
  private final TransactionTemplate transaction;

  public TerminalNotificationRecoveryService(
      TerminalNotificationRecoveryMapper mapper,
      OperationAuditMapper audit,
      TenantIdResolver tenants,
      @Qualifier("transactionManager") PlatformTransactionManager manager) {
    this.mapper = mapper;
    this.audit = audit;
    this.tenants = tenants;
    this.transaction = new TransactionTemplate(manager);
  }

  public List<TerminalDeliveryRecoveryResponse> list(String tenantId, int limit) {
    String tenant = tenants.resolveTenant(tenantId);
    return RlsTenantContextHolder.runWithTenant(
        tenant,
        () -> transaction.execute(status -> {
          mapper.bindTenant(tenant);
          return mapper.list(tenant, limit);
        }));
  }

  public void retry(
      String tenantId,
      Source source,
      Long id,
      TerminalDeliveryRetryRequest request,
      ConsoleRequestMetadata metadata) {
    String tenant = tenants.resolveTenant(tenantId);
    if (!request.confirmedNotDelivered() || EmptyChecks.isBlank(metadata.operatorId())) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.notification.recovery_confirmation_required");
    }
    String aggregateId = source.name() + ":" + id;
    RlsTenantContextHolder.runWithTenant(
        tenant,
        () -> transaction.executeWithoutResult(status -> {
          mapper.bindTenant(tenant);
          TerminalDeliveryRecoveryResponse row = mapper.lock(tenant, source.name(), id);
          if (EmptyChecks.isNull(row)) {
            // 归档仅移动已闭合投递；原人工请求仍须通过冷审计识别幂等。
            row = mapper.archived(tenant, source.name(), id);
          }
          if (EmptyChecks.isNull(row)) {
            throw BizException.of(ResultCode.NOT_FOUND, "error.notification.delivery_not_found");
          }
          String reason = request.reason().trim();
          String reconciliationReference = request.reconciliationReference().trim();
          RecoveryAuditEvidence evidence = new RecoveryAuditEvidence(
              reason,
              reconciliationReference,
              request.expectedAttempt(),
              true,
              row.terminalEventKey());
          String existing = mapper.replayAudit(tenant, aggregateId, request.requestId());
          if (EmptyChecks.isNotNull(existing)) {
            Map<?, ?> existingEvidence = JsonUtils.fromJson(existing, Map.class);
            // 保留完整 JSON 字段集合比较，不能因 DTO 忽略未知字段而误判为同一人工请求。
            Map<String, Object> requestedEvidenceFields = JsonUtils.toMap(evidence);
            if (!requestedEvidenceFields.equals(existingEvidence)) {
              throw BizException.of(
                  ResultCode.STATE_CONFLICT, "error.notification.recovery_request_conflict");
            }
            return;
          }
          if (row.attempt() != request.expectedAttempt()
              || mapper.rearm(tenant, source.name(), id) != 1) {
            throw BizException.of(
                ResultCode.STATE_CONFLICT, "error.notification.delivery_not_recoverable");
          }
          String evidenceJson = JsonUtils.toJson(evidence);
          OperationAuditEvent notificationRetryAuditEvent = OperationAuditEvent.builder()
              .tenantId(tenant)
              .aggregateType("NOTIFICATION_DELIVERY")
              .aggregateId(aggregateId)
              .action("NOTIFICATION_DELIVERY_RETRY")
              .operatorId(metadata.operatorId())
              .result("SUCCESS")
              .paramsJson(evidenceJson)
              .traceId(metadata.traceId())
              .requestId(request.requestId())
              .eventVersion(1)
              .createdAt(BatchDateTimeSupport.utcNow())
              .build();
          int written = audit.insert(notificationRetryAuditEvent);
          if (written != 1)
            throw new IllegalStateException("notification recovery audit was not persisted");
        }));
  }
}
