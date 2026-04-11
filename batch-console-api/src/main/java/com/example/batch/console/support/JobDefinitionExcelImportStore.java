package com.example.batch.console.support;

import java.time.Instant;
import java.util.List;

public interface JobDefinitionExcelImportStore {

    String save(String fileName, String tenantId, List<JobDefinitionRow> rows);

    JobDefinitionExcelSession get(String uploadToken);

    void remove(String uploadToken);

    record JobDefinitionExcelSession(
            String fileName, String tenantId, Instant uploadedAt, List<JobDefinitionRow> rows) {}

    record JobDefinitionRow(
            int rowNo,
            String tenantId,
            String jobCode,
            String jobName,
            String jobType,
            String queueCode,
            String workerGroup,
            String scheduleType,
            String scheduleExpr,
            String calendarCode,
            String windowCode,
            String retryPolicy,
            Integer retryMaxCount,
            Integer timeoutSeconds,
            String shardStrategy,
            String executionHandler,
            String paramSchema,
            String defaultParams,
            Boolean enabled,
            String description) {}
}
