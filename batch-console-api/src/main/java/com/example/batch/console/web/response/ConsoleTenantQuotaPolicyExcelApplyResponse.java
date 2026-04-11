package com.example.batch.console.web.response;

public record ConsoleTenantQuotaPolicyExcelApplyResponse(
        String uploadToken,
        String tenantId,
        Integer appliedRows,
        Integer insertedRows,
        Integer updatedRows) {}
