package com.example.batch.console.web.response.excel;

/** 所有 Excel 上传端点的统一响应。 */
public record ExcelUploadResponse(
    String uploadToken, String fileName, String sheetName, Integer rowCount) {}
