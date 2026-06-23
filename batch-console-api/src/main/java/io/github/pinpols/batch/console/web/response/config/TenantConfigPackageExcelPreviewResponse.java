package io.github.pinpols.batch.console.web.response.config;

import java.util.List;
import java.util.Map;

/** 租户配置包 Excel 预览响应：各 sheet 统计 + 汇总问题列表 + 出错行明细(供前端内联编辑)。 */
public record TenantConfigPackageExcelPreviewResponse(
    String uploadToken,
    String fileName,
    int totalRows,
    int validRows,
    int invalidRows,
    List<SheetStats> sheets,
    List<IssueDto> issues,
    List<ErrorRowDto> errorRows) {

  /** 单个 sheet 的行统计。 */
  public record SheetStats(String sheetName, int totalRows, int validRows, int invalidRows) {}

  /** 单条校验问题，携带所属 sheet 名供前端定位。 */
  public record IssueDto(String sheetName, int rowNo, String columnName, String message) {}

  /**
   * 出错行明细：按 (sheet, rowNo) 聚合的整行单元格值 + 该行所有问题。前端据此在预览页内联编辑该行 （改完调 patch 端点回写 + 重校验），省去“下
   * Excel→改→重传”。{@code values} 迭代顺序 = 该 sheet 列顺序。
   */
  public record ErrorRowDto(
      String sheetName, int rowNo, Map<String, String> values, List<String> messages) {}
}
