package io.github.pinpols.batch.console.domain.notification.service;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 原投递与人工核对审计原子提交,请求身份跨进程持久去重。 */
@Service
public class TerminalNotificationRecoveryService {
  public enum Source { WEBHOOK, RULE }
  private final TerminalNotificationRecoveryMapper mapper;
  private final OperationAuditMapper audit;
  private final TenantIdResolver tenants;
  private final TransactionTemplate transaction;

  public TerminalNotificationRecoveryService(TerminalNotificationRecoveryMapper mapper,
      OperationAuditMapper audit, TenantIdResolver tenants,
      @Qualifier("transactionManager") PlatformTransactionManager manager) {
    this.mapper = mapper;
    this.audit = audit;
    this.tenants = tenants;
    this.transaction = new TransactionTemplate(manager);
  }

  public List<TerminalDeliveryRecoveryResponse> list(String tenantId, int limit) {
    String tenant = tenants.resolveTenant(tenantId);
    return RlsTenantContextHolder.runWithTenant(tenant,
        () -> transaction.execute(status -> mapper.list(tenant, limit)));
  }

  @SuppressWarnings("unchecked")
  public void retry(String tenantId, Source source, Long id, TerminalDeliveryRetryRequest request,
      ConsoleRequestMetadata metadata) {
    String tenant = tenants.resolveTenant(tenantId);
    if (!request.confirmedNotDelivered() || EmptyChecks.isBlank(metadata.operatorId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.notification.recovery_confirmation_required");
    }
    String aggregateId = source.name() + ":" + id;
    RlsTenantContextHolder.runWithTenant(tenant, () -> transaction.executeWithoutResult(status -> {
      var row = mapper.lock(tenant, source.name(), id);
      if (EmptyChecks.isNull(row)) {
        throw BizException.of(ResultCode.NOT_FOUND, "error.notification.delivery_not_found");
      }
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("reason", request.reason().trim());
      evidence.put("reconciliationReference", request.reconciliationReference().trim());
      evidence.put("previousAttempt", request.expectedAttempt());
      evidence.put("confirmedNotDelivered", true);
      evidence.put("terminalEventKey", row.terminalEventKey());
      String existing = mapper.replayAudit(tenant, aggregateId, request.requestId());
      if (EmptyChecks.isNotNull(existing)) {
        if (!evidence.equals(JsonUtils.fromJson(existing, Map.class))) {
          throw BizException.of(ResultCode.STATE_CONFLICT, "error.notification.recovery_request_conflict");
        }
        return;
      }
      if (row.attempt() != request.expectedAttempt() || mapper.rearm(tenant, source.name(), id) != 1) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.notification.delivery_not_recoverable");
      }
      int written = audit.insert(new OperationAuditEvent(tenant, "NOTIFICATION_DELIVERY", aggregateId,
          "NOTIFICATION_DELIVERY_RETRY", metadata.operatorId(), null, "SUCCESS", null, null,
          JsonUtils.toJson(evidence), metadata.traceId(), request.requestId(), null, null, 1,
          BatchDateTimeSupport.utcNow()));
      if (written != 1) throw new IllegalStateException("notification recovery audit was not persisted");
    }));
  }
}
