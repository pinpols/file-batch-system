package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QuotaRuntimeResetScheduler {

    private final QuotaRuntimeStateService quotaRuntimeStateService;
    private final ResourceSchedulerProperties resourceSchedulerProperties;

    @Scheduled(fixedDelayString = "${batch.resource-scheduler.quota-reset-scan-interval-millis:60000}")
    public void reconcile() {
        if (!resourceSchedulerProperties.isQuotaResetEnabled()) {
            return;
        }
        quotaRuntimeStateService.reconcileExpiredStates(resourceSchedulerProperties.getQuotaResetSlidingWindowHours());
    }
}
