package com.example.batch.console.web.response.file;

public record ConsoleBusinessCalendarExcelUploadResponse(
    String uploadToken,
    String fileName,
    String calendarSheetName,
    Integer calendarRowCount,
    String holidaySheetName,
    Integer holidayRowCount) {}
