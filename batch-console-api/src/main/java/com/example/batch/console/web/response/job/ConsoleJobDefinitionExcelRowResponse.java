package com.example.batch.console.web.response.job;

public record ConsoleJobDefinitionExcelRowResponse(
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
