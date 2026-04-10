package com.example.batch.console.web.response;

import java.time.LocalDate;

public record ConsoleCalendarHolidayResponse(
        Long id,
        String calendarCode,
        LocalDate bizDate,
        String dayType,
        String holidayName,
        String description
) {
}
