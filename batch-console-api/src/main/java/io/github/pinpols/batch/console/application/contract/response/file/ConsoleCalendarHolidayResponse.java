package io.github.pinpols.batch.console.application.contract.response.file;

import java.time.LocalDate;

public record ConsoleCalendarHolidayResponse(
    Long id,
    String calendarCode,
    LocalDate bizDate,
    String dayType,
    String holidayName,
    String description) {}
