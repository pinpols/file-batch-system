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

  /**
   * 不带 workbook URL 的便捷构造（向后兼容）。
   *
   * <p>CLAUDE.md「方法参数 ≤6」豁免：record secondary constructor 等同 record 字段定义，封装会破坏对外 API；按
   * CLAUDE.md「构造器（record / DTO / Response / data holder / Spring DI 注入）不受此约束」豁免。
   */
  @SuppressWarnings("PMD.ExcessiveParameterList")
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
