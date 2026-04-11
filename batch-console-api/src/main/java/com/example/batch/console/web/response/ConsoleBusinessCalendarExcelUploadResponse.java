package com.example.batch.console.web.response;

public record ConsoleBusinessCalendarExcelUploadResponse(
        String uploadToken,
        String fileName,
        String calendarSheetName,
        Integer calendarRowCount,
        String holidaySheetName,
        Integer holidayRowCount) {}
