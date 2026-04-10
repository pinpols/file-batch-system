package com.example.batch.console.web.response;

import java.util.List;

public record ConsolePipelineDefinitionExcelRowIssueResponse(
        String sheetName,
        Integer rowNo,
        String rowKey,
        List<String> messages
) {
}
