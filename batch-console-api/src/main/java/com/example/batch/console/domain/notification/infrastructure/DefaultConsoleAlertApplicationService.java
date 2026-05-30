package com.example.batch.console.domain.notification.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.domain.notification.application.ConsoleAlertApplicationService;
import com.example.batch.console.domain.notification.mapper.AlertEventMapper;
import com.example.batch.console.domain.notification.web.response.ConsoleAlertActionResponse;
import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.web.request.ops.AlertActionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    return new ConsoleAlertActionResponse(alertId, tenantId, action, nextStatus);
  }

  private String normalizeStatus(String status) {
    return Texts.hasText(status) ? status.toUpperCase() : STATUS_OPEN;
  }
}
