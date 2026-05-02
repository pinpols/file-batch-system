package com.example.batch.console.web.response.file;

public record ConsoleBusinessCalendarExcelApplyResponse(
    String uploadToken,
    String tenantId,
    Integer appliedCalendarRows,
    Integer insertedCalendars,
    Integer updatedCalendars,
    Integer appliedHolidayRows) {}
