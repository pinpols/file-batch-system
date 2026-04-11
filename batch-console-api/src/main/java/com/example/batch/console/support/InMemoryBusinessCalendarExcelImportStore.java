package com.example.batch.console.support;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryBusinessCalendarExcelImportStore implements BusinessCalendarExcelImportStore {

    private final ConcurrentHashMap<String, ExcelImportSession> sessions =
            new ConcurrentHashMap<>();

    @Override
    public String save(
            String fileName,
            String tenantId,
            List<Map<String, String>> calendarRows,
            List<Map<String, String>> holidayRows) {
        String uploadToken = UUID.randomUUID().toString().replace("-", "");
        sessions.put(
                uploadToken,
                new ExcelImportSession(
                        fileName,
                        tenantId,
                        Instant.now(),
                        List.copyOf(calendarRows),
                        List.copyOf(holidayRows)));
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
