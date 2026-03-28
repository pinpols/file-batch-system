package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QuotaRuntimeResetScheduler {

    private final QuotaRuntimeStateService quotaRuntimeStateService;
    private final ResourceSchedulerProperties resourceSchedulerProperties;

    @Scheduled(fixedDelayString = "${batch.resource-scheduler.quota-reset-scan-interval-millis:60000}")
    @SchedulerLock(name = "quota_runtime_reset", lockAtMostFor = "PT3M", lockAtLeastFor = "PT30S")
    public void scheduledReconcile() {
        reconcile();
    }

    /**
     * Business entrypoint intentionally kept lock-free so tests and manual invocations do not depend
     * on ShedLock state left by background schedulers.
     */
    public void reconcile() {
        if (!resourceSchedulerProperties.isQuotaResetEnabled()) {
            return;
        }
        quotaRuntimeStateService.reconcileExpiredStates(resourceSchedulerProperties.getQuotaResetSlidingWindowHours());
    }
}
