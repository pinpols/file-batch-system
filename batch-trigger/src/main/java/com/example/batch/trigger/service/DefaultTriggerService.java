package com.example.batch.trigger.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.utils.Guard;
import com.example.batch.trigger.domain.OrchestratorTriggerAdapter;
import com.example.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.mapper.BusinessCalendarMapper;
import com.example.batch.trigger.mapper.TriggerRequestMapper;
import com.example.batch.trigger.support.CalendarBizDateDefinition;
import com.example.batch.trigger.support.CalendarHolidayRule;
import com.example.batch.trigger.support.TriggerCalendarConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultTriggerService implements TriggerService {

    private final LaunchAdapterService launchAdapterService;
    private final OrchestratorTriggerAdapter orchestratorTriggerAdapter;
    private final TriggerRequestMapper triggerRequestMapper;
    private final BusinessCalendarMapper businessCalendarMapper;
    private final PlatformTransactionManager transactionManager;

    @Override
    public LaunchResponse launch(TriggerLaunchCommand command) {
        validateRequest(command);
        LaunchRequest launchRequest = launchAdapterService.fromApiRequest(command);
        return persistAndForward(launchRequest, command.idempotencyKey());
    }

    @Override
    public LaunchResponse launchScheduled(ScheduledTriggerCommand command) {
        LaunchRequest launchRequest =
                launchAdapterService.fromScheduledTrigger(command, loadCalendarDefinition(command));
        if (launchRequest.bizDate() == null) {
            return skipScheduled(command);
        }
        String dedupKey = buildScheduledDedupKey(command);
        return persistAndForward(launchRequest, dedupKey);
    }

    @Override
    @Transactional
    public LaunchResponse createPendingCatchUp(ScheduledTriggerCommand command) {
        LaunchRequest launchRequest =
                launchAdapterService.fromScheduledTrigger(command, loadCalendarDefinition(command));
        if (launchRequest.bizDate() == null) {
            return skipScheduled(command);
        }
        String dedupKey = buildScheduledDedupKey(command);
        return persistPending(launchRequest, dedupKey);
    }

    @Override
    @Transactional
    public LaunchResponse approvePendingCatchUp(PendingCatchUpApprovalCommand command) {
        validatePendingApproval(command);
        TriggerRequestEntity pendingRequest =
                triggerRequestMapper.selectByTenantAndRequestId(
                        command.getTenantId(), command.getRequestId());
        Guard.requireFound(pendingRequest, "pending catch-up request not found");
        if (!TriggerType.CATCH_UP.code().equalsIgnoreCase(pendingRequest.getTriggerType())) {
            throw new BizException(ResultCode.BUSINESS_ERROR, "request is not a catch-up request");
        }
        if ("REJECTED".equalsIgnoreCase(pendingRequest.getRequestStatus())) {
            throw new BizException(ResultCode.BUSINESS_ERROR, "request is already rejected");
        }
        if ("LAUNCHED".equalsIgnoreCase(pendingRequest.getRequestStatus())) {
            return new LaunchResponse(pendingRequest.getRequestId(), pendingRequest.getTraceId());
        }
        // H-5: 原子 CAS——只有一个实例可将 ACCEPTED → PROCESSING；
        // 并发审批将看到 0 受影响行并跳过重复分发。
        int claimed =
                triggerRequestMapper.updateRequestStatusConditional(
                        command.getTenantId(), command.getRequestId(), "PROCESSING", "ACCEPTED");
        if (claimed <= 0) {
            // 另一实例已在处理，或状态已发生变化
            TriggerRequestEntity current =
                    triggerRequestMapper.selectByTenantAndRequestId(
                            command.getTenantId(), command.getRequestId());
            return new LaunchResponse(
                    current != null ? current.getRequestId() : pendingRequest.getRequestId(),
                    current != null ? current.getTraceId() : pendingRequest.getTraceId());
        }
        LaunchRequest launchRequest =
                new LaunchRequest(
                        pendingRequest.getTenantId(),
                        pendingRequest.getJobCode(),
                        pendingRequest.getBizDate(),
                        TriggerType.CATCH_UP,
                        pendingRequest.getRequestId(),
                        pendingRequest.getTraceId(),
                        Map.of(
                                "operationType",
                                "CATCH_UP_APPROVAL",
                                "approvalMode",
                                "MANUAL_APPROVAL",
                                "catchUpApproved",
                                true,
                                "reason",
                                command.getReason() == null ? "" : command.getReason()));
        LaunchResponse response = orchestratorTriggerAdapter.sendTrigger(launchRequest);
        triggerRequestMapper.updateRequestStatus(
                command.getTenantId(), command.getRequestId(), "LAUNCHED");
        return response;
    }

    private LaunchResponse persistAndForward(LaunchRequest launchRequest, String dedupKey) {
        // C-6: 去重检查在 REQUIRES_NEW 事务内执行，以缩小竞态窗口。
        // 彻底消除竞态需在 (tenant_id, dedup_key) 上添加数据库 UNIQUE 约束。
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        final TriggerRequestEntity[] existingHolder = new TriggerRequestEntity[1];
        tx.execute(
                _ -> {
                    TriggerRequestEntity existing =
                            triggerRequestMapper.selectByTenantAndDedupKey(
                                    launchRequest.tenantId(), dedupKey);
                    if (existing != null) {
                        existingHolder[0] = existing;
                        return null;
                    }
                    // H-4: 以 PENDING 状态插入，确保 INSERT 与 sendTrigger 之间崩溃时
                    //      记录保持 PENDING（可对账检测），而非 ACCEPTED。
                    TriggerRequestEntity entity = new TriggerRequestEntity();
                    entity.setTenantId(launchRequest.tenantId());
                    entity.setRequestId(launchRequest.requestId());
                    entity.setTriggerType(launchRequest.triggerType().code());
                    entity.setJobCode(launchRequest.jobCode());
                    entity.setBizDate(launchRequest.bizDate());
                    entity.setDedupKey(dedupKey);
                    entity.setRequestStatus("PENDING");
                    entity.setTraceId(launchRequest.traceId());
                    triggerRequestMapper.insert(entity);
                    return null;
                });

        if (existingHolder[0] != null) {
            return new LaunchResponse(
                    existingHolder[0].getRequestId(), existingHolder[0].getTraceId());
        }

        try {
            LaunchResponse response = orchestratorTriggerAdapter.sendTrigger(launchRequest);
            // H-4: 仅在 Orchestrator 确认接收后标记为 ACCEPTED
            triggerRequestMapper.updateRequestStatus(
                    launchRequest.tenantId(), launchRequest.requestId(), "ACCEPTED");
            return response;
        } catch (Exception exception) {
            triggerRequestMapper.updateRequestStatus(
                    launchRequest.tenantId(), launchRequest.requestId(), "REJECTED");
            throw new SystemException(
                    ResultCode.SYSTEM_ERROR, "failed to forward trigger request", exception);
        }
    }

    /** MANUAL_APPROVAL 场景先把 catch-up 请求登记为待审批，不立即转给 orchestrator。 */
    private LaunchResponse persistPending(LaunchRequest launchRequest, String dedupKey) {
        TriggerRequestEntity existing =
                triggerRequestMapper.selectByTenantAndDedupKey(launchRequest.tenantId(), dedupKey);
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
        return command.descriptor().getTenantId()
                + ":"
                + command.descriptor().getJobCode()
                + ":"
                + command.fireTime();
    }

    private LaunchResponse skipScheduled(ScheduledTriggerCommand command) {
        log.info(
                "scheduled trigger skipped by business calendar: tenantId={}, jobCode={},"
                    + " calendarCode={}, fireTime={}",
                command.descriptor().getTenantId(),
                command.descriptor().getJobCode(),
                command.descriptor().getCalendarCode(),
                command.fireTime());
        return LaunchResponse.skipped(command.traceId());
    }

    private CalendarBizDateDefinition loadCalendarDefinition(ScheduledTriggerCommand command) {
        if (command == null || command.descriptor() == null) {
            return null;
        }
        String calendarCode = command.descriptor().getCalendarCode();
        if (!StringUtils.hasText(calendarCode)) {
            return null;
        }
        TriggerCalendarConfig calendar =
                businessCalendarMapper.selectActiveByTenantAndCalendarCode(
                        command.descriptor().getTenantId(), calendarCode);
        if (calendar == null || calendar.getId() == null) {
            // M-14: 配置了日历码但数据库中未找到——告警使错误配置在日志中可见
            log.warn(
                    "calendar definition not found: tenantId={}, calendarCode={} — scheduled"
                        + " trigger will proceed without calendar filtering",
                    command.descriptor().getTenantId(),
                    calendarCode);
            return null;
        }
        List<CalendarHolidayRule> rules =
                businessCalendarMapper.selectHolidayRulesByCalendarId(calendar.getId());
        if (rules == null) {
            rules = List.of();
        }
        Set<LocalDate> holidays =
                rules.stream()
                        .filter(rule -> isDayType(rule, "HOLIDAY"))
                        .map(CalendarHolidayRule::getBizDate)
                        .collect(Collectors.toSet());
        Set<LocalDate> workdayOverrides =
                rules.stream()
                        .filter(rule -> isDayType(rule, "WORKDAY_OVERRIDE"))
                        .map(CalendarHolidayRule::getBizDate)
                        .collect(Collectors.toSet());
        return new CalendarBizDateDefinition(
                calendar.getTimezone(),
                calendar.getCutoffTime(),
                calendar.getHolidayRollRule(),
                holidays,
                workdayOverrides);
    }

    private boolean isDayType(CalendarHolidayRule rule, String expectedType) {
        return rule != null
                && rule.getBizDate() != null
                && expectedType.equalsIgnoreCase(normalize(rule.getDayType()));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void validateRequest(TriggerLaunchCommand command) {
        Guard.require(command != null, "launch command is required");
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new BizException(
                    ResultCode.MISSING_IDEMPOTENCY_KEY,
                    ResultCode.MISSING_IDEMPOTENCY_KEY.defaultMessage());
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
        Guard.require(command != null, "approval command is required");
        if (command.getTenantId() == null || command.getTenantId().isBlank()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "tenantId is required");
        }
        if (command.getRequestId() == null || command.getRequestId().isBlank()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "requestId is required");
        }
    }
}
