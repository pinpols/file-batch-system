package com.example.batch.console.web.response;

public record ConsolePipelineDefinitionExcelUploadResponse(
    String uploadToken, String fileName, Integer pipelineRowCount, Integer stepRowCount) {}
