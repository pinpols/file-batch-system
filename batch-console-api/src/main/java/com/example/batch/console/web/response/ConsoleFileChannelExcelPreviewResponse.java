package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleFileChannelExcelPreviewResponse(
    String uploadToken,
    String fileName,
    String sheetName,
    Integer totalRows,
    Integer validRows,
    Integer invalidRows,
    List<ConsoleFileChannelResponse> rows,
    List<ConsoleFileChannelExcelRowIssueResponse> issues) {}
