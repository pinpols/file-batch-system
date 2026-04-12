package com.example.batch.console.web.response;

public record ConsoleBatchWindowExcelUploadResponse(
    String uploadToken, String fileName, String sheetName, Integer rowCount) {}
