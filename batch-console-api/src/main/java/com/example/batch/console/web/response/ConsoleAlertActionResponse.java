package com.example.batch.console.web.response;

public record ConsoleAlertActionResponse(
        Long alertId,
        String tenantId,
        String action,
        String status
) {
}
