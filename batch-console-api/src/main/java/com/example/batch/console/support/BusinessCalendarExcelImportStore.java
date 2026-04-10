package com.example.batch.console.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface BusinessCalendarExcelImportStore {

    String save(String fileName, String tenantId, List<Map<String, String>> calendarRows, List<Map<String, String>> holidayRows);

    ExcelImportSession get(String uploadToken);

    void remove(String uploadToken);

    record ExcelImportSession(
            String fileName,
            String tenantId,
            Instant uploadedAt,
            List<Map<String, String>> calendarRows,
            List<Map<String, String>> holidayRows
    ) {
    }
}
