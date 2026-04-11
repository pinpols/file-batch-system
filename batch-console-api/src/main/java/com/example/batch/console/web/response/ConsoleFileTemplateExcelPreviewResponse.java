package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleFileTemplateExcelPreviewResponse(
        String uploadToken,
        String fileName,
        String sheetName,
        Integer totalRows,
        Integer validRows,
        Integer invalidRows,
        List<ConsoleFileTemplateResponse> rows,
        List<ConsoleExcelRowIssueResponse> issues) {}
