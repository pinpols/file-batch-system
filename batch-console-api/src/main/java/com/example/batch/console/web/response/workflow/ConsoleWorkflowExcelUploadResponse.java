package com.example.batch.console.web.response.workflow;

public record ConsoleWorkflowExcelUploadResponse(
    String uploadToken,
    String fileName,
    int definitionRows,
    int nodeRows,
    int edgeRows,
    int totalRows) {}
