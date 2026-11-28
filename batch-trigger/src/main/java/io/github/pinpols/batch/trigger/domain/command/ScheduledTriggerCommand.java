package io.github.pinpols.batch.trigger.domain.command;

import java.time.Instant;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;

public record ScheduledTriggerCommand(
        TriggerDescriptor descriptor, Instant fireTime, TriggerType triggerType, String requestId, String traceId) {}
