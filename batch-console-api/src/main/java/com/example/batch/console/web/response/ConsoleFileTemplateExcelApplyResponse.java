package com.example.batch.console.web.response;

public record ConsoleFileTemplateExcelApplyResponse(
    String uploadToken,
    String tenantId,
    Integer appliedRows,
    Integer insertedRows,
    Integer updatedRows) {}
