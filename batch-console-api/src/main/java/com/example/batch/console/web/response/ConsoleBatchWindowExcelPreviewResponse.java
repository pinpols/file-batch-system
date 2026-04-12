package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleBatchWindowExcelPreviewResponse(
    String uploadToken,
    String fileName,
    String sheetName,
    Integer totalRows,
    Integer validRows,
    Integer invalidRows,
    List<ConsoleBatchWindowResponse> rows,
    List<ConsoleBatchWindowExcelRowIssueResponse> issues) {}
