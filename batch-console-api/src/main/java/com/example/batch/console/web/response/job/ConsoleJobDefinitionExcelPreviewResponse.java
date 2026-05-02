package com.example.batch.console.web.response.job;

import java.util.List;

public record ConsoleJobDefinitionExcelPreviewResponse(
    String uploadToken,
    String fileName,
    int totalRows,
    int validRows,
    int invalidRows,
    List<ConsoleJobDefinitionExcelRowResponse> rows,
    List<ConsoleJobDefinitionExcelRowIssueResponse> issues) {}
