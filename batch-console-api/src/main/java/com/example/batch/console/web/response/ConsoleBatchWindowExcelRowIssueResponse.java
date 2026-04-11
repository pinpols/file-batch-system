package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleBatchWindowExcelRowIssueResponse(
        Integer rowNo, String rowKey, String windowCode, List<String> messages) {}
