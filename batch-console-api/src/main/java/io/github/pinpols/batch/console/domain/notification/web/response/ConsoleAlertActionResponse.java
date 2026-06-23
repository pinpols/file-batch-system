package io.github.pinpols.batch.console.domain.notification.web.response;

public record ConsoleAlertActionResponse(
    Long alertId, String tenantId, String action, String status) {}
