package io.github.pinpols.batch.console.shared.command;

import java.time.Instant;
import java.time.LocalDate;

/** 旧版 Console 客户端省略新字段时，仍由 Workflow 持有调度配置。 */
public record ConsoleScheduledOccurrenceCatchUpCommand(
    String tenantId,
    Long workflowDefinitionId,
    Instant scheduledFireTime,
    LocalDate bizDate,
    String requestedBy,
    String reason,
    String idempotencyKey) {}
