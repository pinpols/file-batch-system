package com.example.batch.console.web.response.excel;

import java.util.List;

/**
 * 一键导入（upload + preview + apply 合并）的统一响应。
 *
 * <p>无校验错误时自动 apply 并返回 apply 结果；有校验错误时返回 preview 结果和错误 workbook 下载 URL， 不执行 apply。
 */
public record ExcelQuickImportResponse<R>(
    boolean applied,
    String uploadToken,
    String fileName,
    int totalRows,
    int validRows,
    int invalidRows,
    int insertedRows,
    int updatedRows,
    int skippedRows,
    List<ExcelRowIssue> issues,
    String previewWorkbookUrl) {}
