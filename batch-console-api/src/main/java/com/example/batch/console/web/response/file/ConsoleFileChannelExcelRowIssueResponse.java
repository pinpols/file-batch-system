package com.example.batch.console.web.response.file;

import java.util.List;

public record ConsoleFileChannelExcelRowIssueResponse(
    Integer rowNo, String rowKey, String channelCode, List<String> messages) {}
