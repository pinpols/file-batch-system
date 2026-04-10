package com.example.batch.console.web.response;

public record ConsoleTenantQuotaPolicyExcelUploadResponse(
        String uploadToken,
        String fileName,
        String sheetName,
        Integer rowCount
) {
}
