package com.example.batch.console.web.response;

import java.util.List;

/**
 * 单 sheet Excel preview 端点的统一响应。
 *
 * @param <R> 数据行响应类型（各实体各异，如 ConsoleAlertRoutingResponse）
 */
public record ExcelPreviewResponse<R>(
    String uploadToken,
    String fileName,
    String sheetName,
    int totalRows,
    int validRows,
    int invalidRows,
    List<R> rows,
    List<ExcelRowIssue> issues,
    String previewWorkbookUrl) {

  /** 不带 workbook URL 的便捷构造（向后兼容）。 */
  public ExcelPreviewResponse(
      String uploadToken,
      String fileName,
      String sheetName,
      int totalRows,
      int validRows,
      int invalidRows,
      List<R> rows,
      List<ExcelRowIssue> issues) {
    this(uploadToken, fileName, sheetName, totalRows, validRows, invalidRows, rows, issues, null);
  }
}
