package com.example.batch.console.web.response;

public record ConsoleFileChannelExcelUploadResponse(
        String uploadToken, String fileName, String sheetName, Integer rowCount) {}
