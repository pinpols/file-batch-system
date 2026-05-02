package com.example.batch.console.web.response.excel;

/** 单 sheet Excel apply 端点的统一响应（适用于标准 upsert 模式的 6 个简单服务）。 */
public record ExcelApplyResponse(
    String uploadToken,
    String tenantId,
    int appliedRows,
    int insertedRows,
    int updatedRows,
    int skippedRows) {

  public ExcelApplyResponse(
      String uploadToken, String tenantId, int appliedRows, int insertedRows, int updatedRows) {
    this(uploadToken, tenantId, appliedRows, insertedRows, updatedRows, 0);
  }
}
