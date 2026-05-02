package com.example.batch.orchestrator.domain.entity;

import java.util.Map;

public record JobDefinitionEntity(
    Long id,
    String tenantId,
    String jobCode,
    String jobName,
    String jobType,
    String bizType,
    String scheduleType,
    String scheduleExpr,
    String timezone,
    String workerGroup,
    String queueCode,
    String calendarCode,
    String windowCode,
    String triggerMode,
    Boolean dagEnabled,
    String shardStrategy,
    String retryPolicy,
    Integer retryMaxCount,
    Integer timeoutSeconds,
    String executionHandler,
    Map<String, Object> paramSchema,
    Integer priority,
    Map<String, Object> defaultParams,
    Integer version,
    Boolean enabled,
    String description,
    String executionMode,
    String watermarkField) {}
