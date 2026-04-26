package com.example.batch.console.support;

import java.util.List;
import java.util.Map;

/**
 * 单 sheet Excel 导入会话存储的统一接口。
 *
 * <p>替代各实体独立的 XxxExcelImportStore 接口（AlertRouting / BatchWindow / FileChannel / FileTemplate /
 * ResourceQueue / TenantQuotaPolicy 形态完全一致）。
 *
 * <p>复杂多 sheet 场景（BusinessCalendar / Pipeline / Workflow / TenantConfigPackage）保留各自的 Store 接口。
 */
public interface ExcelImportStore {

  String save(String fileName, String tenantId, String sheetName, List<Map<String, String>> rows);

  ConsoleSingleSheetExcelImportSupport.SingleSheetImportSession get(String uploadToken);

  void remove(String uploadToken);
}
