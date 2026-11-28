package io.github.pinpols.batch.trigger.domain.command;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import lombok.Builder;

import io.github.pinpols.batch.common.enums.TriggerType;

/** 触发用例接收的输入字段。 */
@Builder(toBuilder = true)
public record TriggerLaunchInput(
        String tenantId,
        Long workflowDefinitionId,
        LocalDate bizDate,
        TriggerType triggerType,
        Map<String, Object> params,
        Instant dataIntervalStart,
        Instant dataIntervalEnd,
        Boolean dryRun,
        String externalEventCode,
        String externalEventSource,
        String externalEventId) {}
