package com.example.batch.orchestrator.infrastructure.file;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FileGovernanceLatencyScheduler {

    private final FileGovernanceScheduler fileGovernanceScheduler;

    public FileGovernanceLatencyScheduler(FileGovernanceScheduler fileGovernanceScheduler) {
        this.fileGovernanceScheduler = fileGovernanceScheduler;
    }

    @Scheduled(fixedDelayString = "${batch.file-governance.latency.poll-interval-millis:30000}")
    public void collectLatencyMetrics() {
        fileGovernanceScheduler.collectLatencyMetrics();
    }
}
