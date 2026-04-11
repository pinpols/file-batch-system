package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleTenantQuotaPolicyExcelPreviewResponse(
        String uploadToken,
        String fileName,
        String sheetName,
        Integer totalRows,
        Integer validRows,
        Integer invalidRows,
        List<ConsoleTenantQuotaPolicyResponse> rows,
        List<ConsoleTenantQuotaPolicyExcelRowIssueResponse> issues) {}
