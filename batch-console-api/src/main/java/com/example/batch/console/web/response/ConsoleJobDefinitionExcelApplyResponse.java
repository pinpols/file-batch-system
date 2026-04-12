package com.example.batch.console.web.response;

public record ConsoleJobDefinitionExcelApplyResponse(
    String uploadToken, String tenantId, int appliedRows, int updatedRows) {}
