package com.example.batch.console.web.response;

import java.util.List;

/** 租户配置包 Excel 预览响应：各 sheet 统计 + 汇总问题列表。 */
public record TenantConfigPackageExcelPreviewResponse(
        String uploadToken,
        String fileName,
        int totalRows,
        int validRows,
        int invalidRows,
        List<SheetStats> sheets,
        List<IssueDto> issues) {

    /** 单个 sheet 的行统计。 */
    public record SheetStats(String sheetName, int totalRows, int validRows, int invalidRows) {}

    /** 单条校验问题，携带所属 sheet 名供前端定位。 */
    public record IssueDto(String sheetName, int rowNo, String columnName, String message) {}
}
