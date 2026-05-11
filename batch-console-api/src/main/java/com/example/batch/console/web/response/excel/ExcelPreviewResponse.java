package com.example.batch.console.web.response.excel;

import java.util.List;
import lombok.Builder;

/**
 * 单 sheet Excel preview 端点的统一响应。10 字段记录，按 CLAUDE.md §调用方约束 加 {@code @Builder}，调用方禁止用 inline {@code
 * new}。
 *
 * @param <R> 数据行响应类型（各实体各异，如 ConsoleAlertRoutingResponse）
 */
@Builder
public record ExcelPreviewResponse<R>(
    String uploadToken,
    String fileName,
    String sheetName,
    int totalRows,
    int validRows,
    int invalidRows,
    List<R> rows,
    List<ExcelRowIssue> issues,
    String previewWorkbookUrl,
    ExcelChangeSummary changeSummary) {

  public record ExcelChangeSummary(int insertRows, int updateRows, int upsertRows) {}
}
