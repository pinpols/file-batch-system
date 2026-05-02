package com.example.batch.console.web.response.ops;

public record ConsoleAlertActionResponse(
    Long alertId, String tenantId, String action, String status) {}
