package com.example.batch.console.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface FileTemplateExcelImportStore {

  String save(String fileName, String tenantId, String sheetName, List<Map<String, String>> rows);

  ExcelImportSession get(String uploadToken);

  void remove(String uploadToken);

  record ExcelImportSession(
      String fileName,
      String tenantId,
      String sheetName,
      Instant uploadedAt,
      List<Map<String, String>> rows)
      implements ConsoleSingleSheetExcelImportSupport.SingleSheetImportSession {}
}
