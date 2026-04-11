package com.example.batch.console.web.response;

public record ConsolePipelineDefinitionExcelApplyResponse(
        String uploadToken,
        String tenantId,
        Integer appliedPipelines,
        Integer insertedPipelines,
        Integer updatedPipelines,
        Integer appliedSteps) {}
