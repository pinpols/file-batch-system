package com.example.batch.console.support;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryJobDefinitionExcelImportStore implements JobDefinitionExcelImportStore {

    private final Map<String, JobDefinitionExcelSession> sessions = new ConcurrentHashMap<>();

    @Override
    public String save(String fileName, String tenantId, List<JobDefinitionRow> rows) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new JobDefinitionExcelSession(fileName, tenantId, Instant.now(), rows));
        return token;
    }

    @Override
    public JobDefinitionExcelSession get(String uploadToken) {
        return sessions.get(uploadToken);
    }

    @Override
    public void remove(String uploadToken) {
        sessions.remove(uploadToken);
    }
}
