package io.github.pinpols.batch.console.web.response.file;

import java.util.List;

public record ConsoleBatchWindowExcelRowIssueResponse(
    Integer rowNo, String rowKey, String windowCode, List<String> messages) {}
