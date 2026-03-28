package com.example.batch.console.web.response;

public record ConsoleWorkflowDefinitionExcelRowResponse(
        String tenantId,
        String workflowCode,
        String workflowName,
        String workflowType,
        Integer version,
        Boolean enabled,
        String description
) {
}
