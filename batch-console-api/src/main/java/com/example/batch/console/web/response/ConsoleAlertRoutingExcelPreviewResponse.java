package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleAlertRoutingExcelPreviewResponse(
    String uploadToken,
    String fileName,
    String sheetName,
    Integer totalRows,
    Integer validRows,
    Integer invalidRows,
    List<ConsoleAlertRoutingResponse> rows,
    List<ConsoleAlertRoutingExcelRowIssueResponse> issues) {}
