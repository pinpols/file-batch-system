package com.example.batch.orchestrator.infrastructure.file;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FileGovernanceReconcileScheduler {

    private final FileGovernanceScheduler fileGovernanceScheduler;

    public FileGovernanceReconcileScheduler(FileGovernanceScheduler fileGovernanceScheduler) {
        this.fileGovernanceScheduler = fileGovernanceScheduler;
    }

    @Scheduled(fixedDelayString = "${batch.file-governance.reconcile.poll-interval-millis:60000}")
    public void reconcileObjectStorage() {
        fileGovernanceScheduler.reconcileObjectStorage();
    }
}
