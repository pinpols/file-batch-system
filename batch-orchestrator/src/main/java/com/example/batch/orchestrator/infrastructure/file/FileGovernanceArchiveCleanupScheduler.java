package com.example.batch.orchestrator.infrastructure.file;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FileGovernanceArchiveCleanupScheduler {

    private final FileGovernanceScheduler fileGovernanceScheduler;

    public FileGovernanceArchiveCleanupScheduler(FileGovernanceScheduler fileGovernanceScheduler) {
        this.fileGovernanceScheduler = fileGovernanceScheduler;
    }

    @Scheduled(fixedDelayString = "${batch.file-governance.archive.cleanup-interval-millis:60000}")
    public void cleanupArchivedFiles() {
        fileGovernanceScheduler.cleanupArchivedFiles();
    }
}
