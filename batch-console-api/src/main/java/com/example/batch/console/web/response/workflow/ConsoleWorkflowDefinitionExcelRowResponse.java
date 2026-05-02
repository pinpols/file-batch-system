package com.example.batch.console.web.response.workflow;

public record ConsoleWorkflowDefinitionExcelRowResponse(
    String tenantId,
    String workflowCode,
    String workflowName,
    String workflowType,
    Integer version,
    Boolean enabled,
    String description) {}
