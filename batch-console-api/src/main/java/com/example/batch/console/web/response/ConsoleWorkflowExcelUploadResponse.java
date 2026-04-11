package com.example.batch.console.web.response;

public record ConsoleWorkflowExcelUploadResponse(
        String uploadToken,
        String fileName,
        int definitionRows,
        int nodeRows,
        int edgeRows,
        int totalRows) {}
