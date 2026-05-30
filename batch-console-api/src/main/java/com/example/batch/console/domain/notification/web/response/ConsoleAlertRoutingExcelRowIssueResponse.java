package com.example.batch.console.domain.notification.web.response;

import java.util.List;

public record ConsoleAlertRoutingExcelRowIssueResponse(
    Integer rowNo, String rowKey, String routeCode, List<String> messages) {}
