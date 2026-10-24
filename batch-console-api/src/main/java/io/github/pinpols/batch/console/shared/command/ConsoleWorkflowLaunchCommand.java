package io.github.pinpols.batch.console.shared.command;

import io.github.pinpols.batch.common.enums.TriggerType;
import java.time.LocalDate;
import java.util.Map;

/** Resolved top-level Workflow launch request delegated to Trigger. */
public record ConsoleWorkflowLaunchCommand(
    String tenantId,
    Long workflowDefinitionId,
    LocalDate bizDate,
    TriggerType triggerType,
    Map<String, Object> params,
    String idempotencyKey) {}
