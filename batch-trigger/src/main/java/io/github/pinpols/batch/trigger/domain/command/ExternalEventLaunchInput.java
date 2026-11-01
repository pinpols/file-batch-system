package io.github.pinpols.batch.trigger.domain.command;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.Builder;

/** 触发用例接收的输入字段。 */
@Builder(toBuilder = true)
public record ExternalEventLaunchInput(
    String tenantId,
    String externalEventCode,
    String externalEventId,
    LocalDate bizDate,
    Map<String, Object> params,
    Instant dataIntervalStart,
    Instant dataIntervalEnd,
    Boolean dryRun) {}
