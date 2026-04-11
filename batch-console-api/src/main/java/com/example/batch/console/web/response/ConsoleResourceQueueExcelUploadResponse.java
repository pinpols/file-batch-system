package com.example.batch.console.web.response;

public record ConsoleResourceQueueExcelUploadResponse(
        String uploadToken, String fileName, String sheetName, Integer rowCount) {}
