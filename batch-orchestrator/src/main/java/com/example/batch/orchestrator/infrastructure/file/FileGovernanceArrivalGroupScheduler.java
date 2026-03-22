package com.example.batch.orchestrator.infrastructure.file;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FileGovernanceArrivalGroupScheduler {

    private final FileGovernanceScheduler fileGovernanceScheduler;

    public FileGovernanceArrivalGroupScheduler(FileGovernanceScheduler fileGovernanceScheduler) {
        this.fileGovernanceScheduler = fileGovernanceScheduler;
    }

    @Scheduled(fixedDelayString = "${batch.file-governance.arrival.poll-interval-millis:30000}")
    public void manageFileArrivalGroups() {
        fileGovernanceScheduler.manageFileArrivalGroups();
    }
}
