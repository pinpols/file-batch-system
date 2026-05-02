package com.example.batch.console.web.response.workflow;

public record ConsoleWorkflowEdgeExcelRowResponse(
    String tenantId,
    String workflowCode,
    Integer workflowVersion,
    String fromNodeCode,
    String toNodeCode,
    String edgeType,
    String conditionExpr,
    Boolean enabled) {}
