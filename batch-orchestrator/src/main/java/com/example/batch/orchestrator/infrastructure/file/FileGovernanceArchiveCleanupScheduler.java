package com.example.batch.orchestrator.infrastructure.file;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FileGovernanceArchiveCleanupScheduler {

    private final FileGovernanceScheduler fileGovernanceScheduler;

    @Scheduled(fixedDelayString = "${batch.file-governance.archive.cleanup-interval-millis:60000}")
    @SchedulerLock(name = "file_governance_archive_cleanup", lockAtMostFor = "PT3M", lockAtLeastFor = "PT30S")
    public void cleanupArchivedFiles() {
        fileGovernanceScheduler.cleanupArchivedFiles();
    }
}
