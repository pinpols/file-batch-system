package com.example.batch.console.web.response.file;

import java.util.List;

public record ConsoleBusinessCalendarExcelPreviewResponse(
    String uploadToken,
    String fileName,
    Integer totalCalendarRows,
    Integer validCalendarRows,
    Integer invalidCalendarRows,
    List<ConsoleBusinessCalendarResponse> calendarRows,
    Integer totalHolidayRows,
    Integer validHolidayRows,
    Integer invalidHolidayRows,
    List<ConsoleCalendarHolidayResponse> holidayRows,
    List<ConsoleBusinessCalendarExcelRowIssueResponse> issues) {}
