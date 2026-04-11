package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;

import lombok.RequiredArgsConstructor;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FileGovernanceReconcileScheduler {

    private final FileGovernanceScheduler fileGovernanceScheduler;
    private final OrchestratorGracefulShutdown gracefulShutdown;

    @Scheduled(fixedDelayString = "${batch.file-governance.reconcile.poll-interval-millis:60000}")
    @SchedulerLock(
            name = "file_governance_reconcile",
            lockAtMostFor = "PT3M",
            lockAtLeastFor = "PT30S")
    public void reconcileObjectStorage() {
        if (gracefulShutdown.isDraining()) {
            return;
        }
        fileGovernanceScheduler.reconcileObjectStorage();
    }
}
