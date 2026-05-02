package com.example.batch.console.web.response.workflow;

public record ConsolePipelineDefinitionExcelApplyResponse(
    String uploadToken,
    String tenantId,
    Integer appliedPipelines,
    Integer insertedPipelines,
    Integer updatedPipelines,
    Integer appliedSteps) {}
