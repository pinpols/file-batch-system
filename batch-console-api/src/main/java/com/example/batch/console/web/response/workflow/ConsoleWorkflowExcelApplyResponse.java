package com.example.batch.console.web.response.workflow;

public record ConsoleWorkflowExcelApplyResponse(
    String uploadToken,
    String tenantId,
    int appliedDefinitions,
    int appliedNodes,
    int appliedEdges,
    int insertedDefinitions,
    int updatedDefinitions,
    int insertedNodes,
    int updatedNodes,
    int insertedEdges,
    int updatedEdges) {}
