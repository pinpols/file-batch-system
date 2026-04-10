package com.example.batch.console.web.response;

import java.util.List;

public record ConsolePipelineDefinitionExcelPreviewResponse(
        String uploadToken,
        String fileName,
        Integer totalPipelineRows,
        Integer validPipelineRows,
        Integer invalidPipelineRows,
        Integer totalStepRows,
        Integer validStepRows,
        Integer invalidStepRows,
        List<PipelineDefinitionDetailResponse> pipelines,
        List<ConsolePipelineDefinitionExcelRowIssueResponse> issues
) {
}
