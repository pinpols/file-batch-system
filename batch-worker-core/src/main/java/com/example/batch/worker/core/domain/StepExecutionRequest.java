package com.example.batch.worker.core.domain;

import java.util.Map;

public record StepExecutionRequest(
        String tenantId,
        String jobCode,
        String stepCode,
        String workerId,
        Map<String, Object> context
) {
}
