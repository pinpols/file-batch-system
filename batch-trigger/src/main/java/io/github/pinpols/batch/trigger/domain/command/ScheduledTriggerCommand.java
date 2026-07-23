package io.github.pinpols.batch.trigger.domain.command;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.Instant;

public record ScheduledTriggerCommand(
    TriggerDescriptor descriptor,
    Instant fireTime,
    TriggerType triggerType,
    String requestId,
    String traceId) {}
