package io.github.pinpols.batch.console.shared.view;

import lombok.Builder;

@Builder
public record ConsoleOpsSummaryResponse(
    String tenantId,
    long pendingApprovals,
    long openAlerts,
    long criticalAlerts,
    long runningJobs,
    long failedJobs,
    long slaBreaches,
    long onlineWorkers,
    long drainingWorkers,
    long offlineWorkers,
    long outboxRetryBacklog,
    long outboxDeliveryFailures) {}
