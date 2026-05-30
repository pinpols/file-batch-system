package com.example.batch.console.domain.file.web.response;

import java.util.List;

public record ConsoleFileChannelExcelRowIssueResponse(
    Integer rowNo, String rowKey, String channelCode, List<String> messages) {}
