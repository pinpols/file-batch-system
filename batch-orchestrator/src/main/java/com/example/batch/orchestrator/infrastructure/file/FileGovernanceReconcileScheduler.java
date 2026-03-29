package com.example.batch.orchestrator.infrastructure.file;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FileGovernanceReconcileScheduler {

    private final FileGovernanceScheduler fileGovernanceScheduler;

    @Scheduled(fixedDelayString = "${batch.file-governance.reconcile.poll-interval-millis:60000}")
    @SchedulerLock(name = "file_governance_reconcile", lockAtMostFor = "PT3M", lockAtLeastFor = "PT30S")
    public void reconcileObjectStorage() {
        fileGovernanceScheduler.reconcileObjectStorage();
    }
}
