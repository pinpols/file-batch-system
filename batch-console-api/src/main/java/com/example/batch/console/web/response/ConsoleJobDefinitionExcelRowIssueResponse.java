package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleJobDefinitionExcelRowIssueResponse(
    String sheetName, Integer rowNo, String rowKey, String jobCode, List<String> messages) {}
