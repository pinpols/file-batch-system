package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleExcelRowIssueResponse(
    Integer rowNo, String rowKey, String templateCode, Integer version, List<String> messages) {}
