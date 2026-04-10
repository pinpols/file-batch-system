package com.example.batch.console.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface PipelineDefinitionExcelImportStore {

    String save(String fileName, String tenantId, List<Map<String, String>> pipelineRows, List<Map<String, String>> stepRows);

    ExcelImportSession get(String uploadToken);

    void remove(String uploadToken);

    record ExcelImportSession(
            String fileName,
            String tenantId,
            Instant uploadedAt,
            List<Map<String, String>> pipelineRows,
            List<Map<String, String>> stepRows
    ) {
    }
}
