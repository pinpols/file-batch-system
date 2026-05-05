package com.example.batch.console.support.excel;

import com.example.batch.common.time.BatchDateTimeSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryPipelineDefinitionExcelImportStore
    implements PipelineDefinitionExcelImportStore {

  private final ConcurrentHashMap<String, ExcelImportSession> sessions = new ConcurrentHashMap<>();

  @Override
  public String save(
      String fileName,
      String tenantId,
      List<Map<String, String>> pipelineRows,
      List<Map<String, String>> stepRows) {
    String uploadToken = UUID.randomUUID().toString().replace("-", "");
    sessions.put(
        uploadToken,
        new ExcelImportSession(
            fileName,
            tenantId,
            BatchDateTimeSupport.utcNow(),
            List.copyOf(pipelineRows),
            List.copyOf(stepRows)));
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
}
