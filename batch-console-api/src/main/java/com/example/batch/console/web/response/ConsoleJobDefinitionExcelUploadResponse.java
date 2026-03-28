package com.example.batch.console.web.response;

public record ConsoleJobDefinitionExcelUploadResponse(
        String uploadToken,
        String fileName,
        int rowCount
) {
}
