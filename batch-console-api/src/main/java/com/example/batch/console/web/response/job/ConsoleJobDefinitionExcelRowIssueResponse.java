package com.example.batch.console.web.response.job;

import java.util.List;

public record ConsoleJobDefinitionExcelRowIssueResponse(
    String sheetName, Integer rowNo, String rowKey, String jobCode, List<String> messages) {}
