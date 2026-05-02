package com.example.batch.console.web.response.workflow;

public record ConsolePipelineDefinitionExcelUploadResponse(
    String uploadToken, String fileName, Integer pipelineRowCount, Integer stepRowCount) {}
