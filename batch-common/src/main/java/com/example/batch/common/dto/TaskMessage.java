package com.example.batch.common.dto;

import java.util.Map;

public record TaskMessage(
    String tenantId,
    String jobCode,
    String instanceNo,
    String partitionKey,
    String taskType,
    String traceId,
    Map<String, Object> payload) {}
