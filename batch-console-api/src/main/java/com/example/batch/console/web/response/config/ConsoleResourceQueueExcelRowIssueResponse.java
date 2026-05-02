package com.example.batch.console.web.response.config;

import java.util.List;

public record ConsoleResourceQueueExcelRowIssueResponse(
    Integer rowNo, String rowKey, String queueCode, List<String> messages) {}
