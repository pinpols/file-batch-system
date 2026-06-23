package io.github.pinpols.batch.console.web.response.excel;

import java.util.List;

public record ConsoleExcelRowIssueResponse(
    Integer rowNo, String rowKey, String templateCode, Integer version, List<String> messages) {}
