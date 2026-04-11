package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.ConsoleAlertApplicationService;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.mapper.AlertEventMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.AlertActionRequest;
import com.example.batch.console.web.response.ConsoleAlertActionResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
                Guard.requireFound(
                        alertEventMapper.selectById(tenantId, alertId), "alert event not found");
        String currentStatus = normalizeStatus(entity.getStatus());
        if (nextStatus.equals(currentStatus)) {
            return new ConsoleAlertActionResponse(alertId, tenantId, action, currentStatus);
        }
        if (STATUS_CLOSED.equals(currentStatus) && !STATUS_CLOSED.equals(nextStatus)) {
            throw new BizException(
                    ResultCode.STATE_CONFLICT, "closed alert cannot be reopened by this action");
        }
        int updated = alertEventMapper.updateStatus(tenantId, alertId, nextStatus);
        if (updated == 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "failed to update alert status");
        }
        domainEventPublisher.publishChanged(tenantId, "alerts", "alert-updated");
        domainEventPublisher.publishSummaryRefresh(tenantId);
        return new ConsoleAlertActionResponse(alertId, tenantId, action, nextStatus);
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.toUpperCase() : STATUS_OPEN;
    }
}
