package com.example.batch.trigger.domain.command;

import com.example.batch.common.enums.TriggerType;
import com.example.batch.trigger.support.TriggerDescriptor;
import java.time.Instant;

public record ScheduledTriggerCommand(
        TriggerDescriptor descriptor,
        Instant fireTime,
        TriggerType triggerType,
        String requestId,
        String traceId
) {
}
