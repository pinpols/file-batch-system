package io.github.pinpols.batch.trigger.domain.command;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** 触发用例接收的输入字段。 */
@Builder(toBuilder = true)
public record ScheduledOccurrenceCatchUpInput(
    String tenantId,
    Long workflowDefinitionId,
    Instant scheduledFireTime,
    LocalDate bizDate,
    String requestedBy,
    String reason) {}
