package com.example.batch.console.web.response.job;

import java.time.Instant;

/** {@code batch.job_execution_log} 单行日志(console 展示视图)。 */
public record ConsoleJobExecutionLogResponse(
    Long id,
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    String logLevel,
    String logType,
    String traceId,
    String message,
    String detailRef,
    String extraJson,
    Instant createdAt) {}
