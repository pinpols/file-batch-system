package com.example.batch.orchestrator.application.plan;

public record SchedulePlanCommand(
        String tenantId,
        String jobCode,
        String bizDate,
        java.util.Map<String, Object> params
) {
}
