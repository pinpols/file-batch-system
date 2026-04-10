package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleResourceQueueExcelPreviewResponse(
        String uploadToken,
        String fileName,
        String sheetName,
        Integer totalRows,
        Integer validRows,
        Integer invalidRows,
        List<ConsoleResourceQueueResponse> rows,
        List<ConsoleResourceQueueExcelRowIssueResponse> issues
) {
}
