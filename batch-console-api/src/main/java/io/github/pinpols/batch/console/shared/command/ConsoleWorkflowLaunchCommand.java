package io.github.pinpols.batch.console.shared.command;

import java.time.LocalDate;
import java.util.Map;

import io.github.pinpols.batch.common.enums.TriggerType;

/** 已解析且通过认证的单条定时 Workflow 触发补扫操作。 */
public record ConsoleWorkflowLaunchCommand(
        String tenantId,
        Long workflowDefinitionId,
        LocalDate bizDate,
        TriggerType triggerType,
        Map<String, Object> params,
        String idempotencyKey) {}
