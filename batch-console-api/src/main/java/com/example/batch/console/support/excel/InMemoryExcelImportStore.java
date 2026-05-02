package com.example.batch.console.support.excel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 基于 ConcurrentHashMap 的单 sheet Excel 导入会话存储（开发/单机部署）。 */
@Component
public class InMemoryExcelImportStore implements ExcelImportStore {

  private final ConcurrentHashMap<String, ExcelImportSession> sessions = new ConcurrentHashMap<>();

  @Override
  public String save(
      String fileName, String tenantId, String sheetName, List<Map<String, String>> rows) {
    String uploadToken = UUID.randomUUID().toString().replace("-", "");
    sessions.put(
        uploadToken,
        new ExcelImportSession(fileName, tenantId, sheetName, Instant.now(), List.copyOf(rows)));
    return uploadToken;
  }

  @Override
  public ExcelImportSession get(String uploadToken) {
    return sessions.get(uploadToken);
  }

  @Override
  public void remove(String uploadToken) {
    sessions.remove(uploadToken);
  }

  record ExcelImportSession(
      String fileName,
      String tenantId,
      String sheetName,
      Instant uploadedAt,
      List<Map<String, String>> rows)
      implements ConsoleSingleSheetExcelImportSupport.SingleSheetImportSession {}
}
