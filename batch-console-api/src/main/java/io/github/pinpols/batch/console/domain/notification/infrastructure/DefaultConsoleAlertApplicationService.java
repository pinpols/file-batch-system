package io.github.pinpols.batch.console.domain.notification.infrastructure;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleAlertApplicationService;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerSilenceBridge;
import io.github.pinpols.batch.console.domain.notification.web.response.ConsoleAlertActionResponse;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.web.request.ops.AlertActionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** {@link ConsoleAlertApplicationService} 的默认实现： 仅执行告警状态流转，不引入额外的告警事件表。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleAlertApplicationService implements ConsoleAlertApplicationService {

  private static final String STATUS_OPEN = "OPEN";
  private static final String STATUS_ACKED = "ACKED";
  private static final String STATUS_SUPPRESSED = "SUPPRESSED";
  private static final String STATUS_CLOSED = "CLOSED";

  private final ConsoleTenantGuard tenantGuard;
  private final AlertEventMapper alertEventMapper;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private final AlertmanagerSilenceBridge alertmanagerSilenceBridge;

  @Override
  @Transactional
  public ConsoleAlertActionResponse ack(
      Long alertId, AlertActionRequest request, String idempotencyKey) {
    return transition(alertId, request, STATUS_ACKED, "ack");
  }

  @Override
  @Transactional
  public ConsoleAlertActionResponse silence(
      Long alertId, AlertActionRequest request, String idempotencyKey) {
    return transition(alertId, request, STATUS_SUPPRESSED, "silence");
  }

  @Override
  @Transactional
  public ConsoleAlertActionResponse close(
      Long alertId, AlertActionRequest request, String idempotencyKey) {
    return transition(alertId, request, STATUS_CLOSED, "close");
  }

  private ConsoleAlertActionResponse transition(
      Long alertId, AlertActionRequest request, String nextStatus, String action) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    AlertEventEntity entity =
        Guard.requireFound(alertEventMapper.selectById(tenantId, alertId), "alert event not found");
    String currentStatus = normalizeStatus(entity.getStatus());
    if (nextStatus.equals(currentStatus)) {
      return new ConsoleAlertActionResponse(alertId, tenantId, action, currentStatus);
    }
    if (STATUS_CLOSED.equals(currentStatus) && !STATUS_CLOSED.equals(nextStatus)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.alert.closed_cannot_reopen");
    }
    int updated = alertEventMapper.updateStatus(tenantId, alertId, nextStatus);
    if (updated == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.alert.update_status_failed");
    }
    domainEventPublisher.publishChanged(tenantId, "alerts", "alert-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
    // silence/close → AM 单向桥接(迁移方案 §3.5,失败隔离):fbs 状态与审计已在事务内落定,桥接尽力而为。
    bridgeToAlertmanager(entity, nextStatus);
    return new ConsoleAlertActionResponse(alertId, tenantId, action, nextStatus);
  }

  private void bridgeToAlertmanager(AlertEventEntity entity, String nextStatus) {
    // M-1:与 emit 直连一致,桥接放事务提交后 —— 提交失败时 AM 不该已收到 silence/resolved。
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatchBridge(entity, nextStatus);
            }
          });
    } else {
      dispatchBridge(entity, nextStatus);
    }
  }

  private void dispatchBridge(AlertEventEntity entity, String nextStatus) {
    if (STATUS_SUPPRESSED.equals(nextStatus)) {
      // 时长走桥接默认(AlertActionRequest 无时长维度);后续如需可扩展请求字段。
      alertmanagerSilenceBridge.silence(entity, null);
    } else if (STATUS_CLOSED.equals(nextStatus)) {
      alertmanagerSilenceBridge.resolve(entity);
    }
  }

  private String normalizeStatus(String status) {
    return Texts.hasText(status) ? status.toUpperCase() : STATUS_OPEN;
  }
}
