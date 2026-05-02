package com.example.batch.console.web.response.job;

public record ConsoleJobDefinitionExcelApplyResponse(
    String uploadToken, String tenantId, int appliedRows, int updatedRows) {}
