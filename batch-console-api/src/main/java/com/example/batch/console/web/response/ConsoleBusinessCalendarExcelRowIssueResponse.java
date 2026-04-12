package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleBusinessCalendarExcelRowIssueResponse(
    String sheetName, Integer rowNo, String rowKey, List<String> messages) {}
