package io.github.pinpols.batch.console.application.contract.response.config;

import java.util.List;

/** 租户配置包 11-Sheet 填写说明，用于 Console 页面直接展示模板字段口径。 */
public record TenantConfigPackageExcelGuideResponse(List<SheetGuide> sheets) {

  /** 单个 Sheet 的字段说明。 */
  public record SheetGuide(String sheetName, String appliesTo, List<ColumnGuide> columns) {}

  /** 单列填写说明。 */
  public record ColumnGuide(
      String columnName,
      boolean required,
      boolean readOnly,
      String guideLevel,
      String format,
      List<String> allowedValues,
      String description,
      String example,
      String fillExample,
      String defaultBehavior,
      String appliesTo) {}
}
