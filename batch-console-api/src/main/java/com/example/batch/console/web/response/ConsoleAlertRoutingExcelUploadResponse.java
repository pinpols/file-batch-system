package com.example.batch.console.web.response;

public record ConsoleAlertRoutingExcelUploadResponse(
    String uploadToken, String fileName, String sheetName, Integer rowCount) {}
