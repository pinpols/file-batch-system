package com.example.batch.console.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryResourceQueueExcelImportStore implements ResourceQueueExcelImportStore {

    private final ConcurrentHashMap<String, ExcelImportSession> sessions = new ConcurrentHashMap<>();

    @Override
    public String save(String fileName, String tenantId, String sheetName, List<Map<String, String>> rows) {
        String uploadToken = UUID.randomUUID().toString().replace("-", "");
        sessions.put(uploadToken, new ExcelImportSession(fileName, tenantId, sheetName, Instant.now(), List.copyOf(rows)));
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
