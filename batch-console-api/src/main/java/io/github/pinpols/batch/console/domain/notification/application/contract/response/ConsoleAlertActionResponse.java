package io.github.pinpols.batch.console.domain.notification.application.contract.response;

public record ConsoleAlertActionResponse(
    Long alertId, String tenantId, String action, String status) {}
