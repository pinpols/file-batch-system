package com.example.batch.console.web.response;

public record ConsoleFileTemplateExcelUploadResponse(
        String uploadToken,
        String fileName,
        String sheetName,
        Integer rowCount
) {
}
