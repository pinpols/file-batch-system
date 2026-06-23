package io.github.pinpols.batch.orchestrator.application.plan;

import java.util.Map;

public record SchedulePlanCommand(
    String tenantId, String jobCode, String bizDate, Map<String, Object> params) {}
