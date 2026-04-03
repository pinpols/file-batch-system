package com.example.batch.orchestrator.infrastructure.file;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileGovernanceSchedulersTest {

    @Mock
    private FileGovernanceScheduler fileGovernanceScheduler;

    private FileGovernanceArchiveCleanupScheduler archiveCleanupScheduler;
    private FileGovernanceReconcileScheduler reconcileScheduler;
    private FileGovernanceArrivalGroupScheduler arrivalGroupScheduler;
    private FileGovernanceLatencyScheduler latencyScheduler;

    @BeforeEach
    void setUp() {
        archiveCleanupScheduler = new FileGovernanceArchiveCleanupScheduler(fileGovernanceScheduler);
        reconcileScheduler = new FileGovernanceReconcileScheduler(fileGovernanceScheduler);
        arrivalGroupScheduler = new FileGovernanceArrivalGroupScheduler(fileGovernanceScheduler);
        latencyScheduler = new FileGovernanceLatencyScheduler(fileGovernanceScheduler);
    }

    @Test
    void shouldDelegateArchiveCleanup() {
        archiveCleanupScheduler.cleanupArchivedFiles();

        verify(fileGovernanceScheduler).cleanupArchivedFiles();
    }

    @Test
    void shouldDelegateReconcile() {
        reconcileScheduler.reconcileObjectStorage();

        verify(fileGovernanceScheduler).reconcileObjectStorage();
    }

    @Test
    void shouldDelegateArrivalGroupManagement() {
        arrivalGroupScheduler.manageFileArrivalGroups();

        verify(fileGovernanceScheduler).manageFileArrivalGroups();
    }

    @Test
    void shouldDelegateLatencyCollection() {
        latencyScheduler.collectLatencyMetrics();

        verify(fileGovernanceScheduler).collectLatencyMetrics();
    }
}
