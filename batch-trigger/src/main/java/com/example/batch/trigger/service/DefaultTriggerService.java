package com.example.batch.trigger.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;
import com.example.batch.trigger.domain.OrchestratorTriggerAdapter;
import com.example.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.domain.entity.TriggerRequestEntity;
import com.example.batch.trigger.mapper.TriggerRequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultTriggerService implements TriggerService {

    private final LaunchAdapterService launchAdapterService;
    private final OrchestratorTriggerAdapter orchestratorTriggerAdapter;
    private final TriggerRequestMapper triggerRequestMapper;

    @Override
    @Transactional
    public LaunchResponse launch(TriggerLaunchCommand command) {
        validateRequest(command);
        LaunchRequest launchRequest = launchAdapterService.fromApiRequest(command);
        return persistAndForward(launchRequest, command.idempotencyKey());
    }

    @Override
    @Transactional
    public LaunchResponse launchScheduled(ScheduledTriggerCommand command) {
        LaunchRequest launchRequest = launchAdapterService.fromScheduledTrigger(command);
        String dedupKey = buildScheduledDedupKey(command);
        return persistAndForward(launchRequest, dedupKey);
    }

    @Override
    @Transactional
    public LaunchResponse createPendingCatchUp(ScheduledTriggerCommand command) {
        LaunchRequest launchRequest = launchAdapterService.fromScheduledTrigger(command);
        String dedupKey = buildScheduledDedupKey(command);
        return persistPending(launchRequest, dedupKey);
    }

    @Override
    @Transactional
    public LaunchResponse approvePendingCatchUp(PendingCatchUpApprovalCommand command) {
        validatePendingApproval(command);
        TriggerRequestEntity pendingRequest = triggerRequestMapper.selectByTenantAndRequestId(
                command.getTenantId(),
                command.getRequestId()
        );
        if (pendingRequest == null) {
            throw new BizException(ResultCode.NOT_FOUND, "pending catch-up request not found");
        }
        if (!TriggerType.CATCH_UP.code().equalsIgnoreCase(pendingRequest.getTriggerType())) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "request is not a catch-up request");
        }
        if ("REJECTED".equalsIgnoreCase(pendingRequest.getRequestStatus())) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "request is already rejected");
        }
        LaunchRequest launchRequest = new LaunchRequest(
                pendingRequest.getTenantId(),
                pendingRequest.getJobCode(),
                pendingRequest.getBizDate(),
                TriggerType.CATCH_UP,
                pendingRequest.getRequestId(),
                pendingRequest.getTraceId(),
                java.util.Map.of(
                        "operationType", "CATCH_UP_APPROVAL",
                        "approvalMode", "MANUAL_APPROVAL",
                        "catchUpApproved", true,
                        "reason", command.getReason() == null ? "" : command.getReason()
                )
        );
        LaunchResponse response = orchestratorTriggerAdapter.sendTrigger(launchRequest);
        triggerRequestMapper.updateRequestStatus(command.getTenantId(), command.getRequestId(), "LAUNCHED");
        return response;
    }

    private LaunchResponse persistAndForward(LaunchRequest launchRequest, String dedupKey) {
        TriggerRequestEntity existing = triggerRequestMapper.selectByTenantAndDedupKey(launchRequest.tenantId(), dedupKey);
        if (existing != null) {
            return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
        }

        TriggerRequestEntity entity = new TriggerRequestEntity();
        entity.setTenantId(launchRequest.tenantId());
        entity.setRequestId(launchRequest.requestId());
        entity.setTriggerType(launchRequest.triggerType().code());
        entity.setJobCode(launchRequest.jobCode());
        entity.setBizDate(launchRequest.bizDate());
        entity.setDedupKey(dedupKey);
        entity.setRequestStatus("ACCEPTED");
        entity.setTraceId(launchRequest.traceId());
        triggerRequestMapper.insert(entity);

        try {
            return orchestratorTriggerAdapter.sendTrigger(launchRequest);
        } catch (Exception exception) {
            triggerRequestMapper.updateRequestStatus(launchRequest.tenantId(), launchRequest.requestId(), "REJECTED");
            throw new SystemException(ResultCode.SYSTEM_ERROR, "failed to forward trigger request", exception);
        }
    }

    /**
     * MANUAL_APPROVAL 场景先把 catch-up 请求登记为待审批，不立即转给 orchestrator。
     */
    private LaunchResponse persistPending(LaunchRequest launchRequest, String dedupKey) {
        TriggerRequestEntity existing = triggerRequestMapper.selectByTenantAndDedupKey(launchRequest.tenantId(), dedupKey);
        if (existing != null) {
            return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
        }
        TriggerRequestEntity entity = new TriggerRequestEntity();
        entity.setTenantId(launchRequest.tenantId());
        entity.setRequestId(launchRequest.requestId());
        entity.setTriggerType(TriggerType.CATCH_UP.code());
        entity.setJobCode(launchRequest.jobCode());
        entity.setBizDate(launchRequest.bizDate());
        entity.setDedupKey(dedupKey);
        entity.setRequestStatus("ACCEPTED");
        entity.setTraceId(launchRequest.traceId());
        triggerRequestMapper.insert(entity);
        return new LaunchResponse(entity.getRequestId(), entity.getTraceId());
    }

    private String buildScheduledDedupKey(ScheduledTriggerCommand command) {
        return command.descriptor().getTenantId() + ":" + command.descriptor().getJobCode() + ":" + command.fireTime();
    }

    private void validateRequest(TriggerLaunchCommand command) {
        if (command == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "launch command is required");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new BizException(ResultCode.MISSING_IDEMPOTENCY_KEY, ResultCode.MISSING_IDEMPOTENCY_KEY.defaultMessage());
        }
        if (command.request() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "request body is required");
        }
        if (command.request().getTenantId() == null || command.request().getTenantId().isBlank()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "tenantId is required");
        }
        if (command.request().getJobCode() == null || command.request().getJobCode().isBlank()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "jobCode is required");
        }
        if (command.request().getBizDate() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "bizDate is required");
        }
        if (command.request().getTriggerType() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "triggerType is required");
        }
    }

    private void validatePendingApproval(PendingCatchUpApprovalCommand command) {
        if (command == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "approval command is required");
        }
        if (command.getTenantId() == null || command.getTenantId().isBlank()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "tenantId is required");
        }
        if (command.getRequestId() == null || command.getRequestId().isBlank()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "requestId is required");
        }
    }
}
