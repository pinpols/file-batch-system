package io.github.pinpols.batch.console.web.response.file;

import java.time.Instant;

public record ConsoleBusinessCalendarResponse(
    Long id,
    String tenantId,
    String calendarCode,
    String calendarName,
    String timezone,
    String holidayRollRule,
    String catchUpPolicy,
    Integer catchUpMaxDays,
    Boolean enabled,
    Instant createdAt,
    Instant updatedAt) {}
