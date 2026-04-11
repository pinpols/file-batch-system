package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleWorkflowExcelPreviewResponse(
        String uploadToken,
        String fileName,
        int definitionRows,
        int nodeRows,
        int edgeRows,
        int totalRows,
        int validRows,
        int invalidRows,
        List<ConsoleWorkflowDefinitionExcelRowResponse> definitions,
        List<ConsoleWorkflowNodeExcelRowResponse> nodes,
        List<ConsoleWorkflowEdgeExcelRowResponse> edges,
        List<ConsoleWorkflowExcelRowIssueResponse> issues) {}
