package com.example.batch.console.web.response.job;

public record ConsoleJobDefinitionExcelUploadResponse(
    String uploadToken, String fileName, int rowCount) {}
