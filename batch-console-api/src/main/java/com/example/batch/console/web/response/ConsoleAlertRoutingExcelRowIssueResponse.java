package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleAlertRoutingExcelRowIssueResponse(
    Integer rowNo, String rowKey, String routeCode, List<String> messages) {}
