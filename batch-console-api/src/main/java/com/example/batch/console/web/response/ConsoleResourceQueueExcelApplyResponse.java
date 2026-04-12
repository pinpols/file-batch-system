package com.example.batch.console.web.response;

public record ConsoleResourceQueueExcelApplyResponse(
    String uploadToken,
    String tenantId,
    Integer appliedRows,
    Integer insertedRows,
    Integer updatedRows) {}
