package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleWorkflowExcelRowIssueResponse(
        String sheetName,
        Integer rowNo,
        String rowKey,
        String workflowCode,
        Integer workflowVersion,
        List<String> messages) {}
