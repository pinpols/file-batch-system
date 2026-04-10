package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.application.service.WorkerDrainGovernanceService;
import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import com.example.batch.orchestrator.domain.value.JsonbString;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.Mockito.anyString;

@ExtendWith(MockitoExtension.class)
class WorkerDrainTimeoutSchedulerTest {

    @Mock
    private WorkerRegistryRepository workerRegistryRepository;

    @Mock
    private WorkerDrainGovernanceService workerDrainGovernanceService;

    @Mock
    private OrchestratorGracefulShutdown gracefulShutdown;

    private WorkerDrainProperties workerDrainProperties;
    private WorkerDrainTimeoutScheduler scheduler;

    @BeforeEach
    void setUp() {
        workerDrainProperties = new WorkerDrainProperties();
        workerDrainProperties.setEnabled(true);
        scheduler = new WorkerDrainTimeoutScheduler(
                workerRegistryRepository,
                workerDrainGovernanceService,
                workerDrainProperties,
                gracefulShutdown
        );
    }

    @Test
    void shouldSkipWhenDisabled() {
        workerDrainProperties.setEnabled(false);

        scheduler.expireDrains();

        verify(workerRegistryRepository, never()).findByStatus(WorkerRegistryStatus.DRAINING.code());
        verify(workerDrainGovernanceService, never()).takeoverAfterDrainTimeout(anyString(), anyString());
    }

    @Test
    void shouldTakeOverOnlyExpiredDrainingWorkers() {
        Instant now = Instant.now();
        WorkerRegistryRecord expired = worker("t1", "worker-expired", now.minusSeconds(120), now.minusSeconds(1));
        WorkerRegistryRecord future = worker("t1", "worker-future", now.minusSeconds(120), now.plusSeconds(60));
        WorkerRegistryRecord missingDeadline = worker("t1", "worker-missing", now.minusSeconds(120), null);

        when(workerRegistryRepository.findByStatus(WorkerRegistryStatus.DRAINING.code()))
                .thenReturn(Arrays.asList(expired, future, missingDeadline, null));

        scheduler.expireDrains();

        verify(workerDrainGovernanceService).takeoverAfterDrainTimeout("t1", "worker-expired");
        verify(workerDrainGovernanceService, never()).takeoverAfterDrainTimeout("t1", "worker-future");
        verify(workerDrainGovernanceService, never()).takeoverAfterDrainTimeout("t1", "worker-missing");
    }

    private static WorkerRegistryRecord worker(String tenantId,
                                               String workerCode,
                                               Instant heartbeatAt,
                                               Instant drainDeadlineAt) {
        return new WorkerRegistryRecord(
                1L,
                tenantId,
                workerCode,
                "WG-1",
                JsonbString.of("{}"),
                null,
                WorkerRegistryStatus.DRAINING.code(),
                heartbeatAt,
                3,
                heartbeatAt.minusSeconds(30),
                drainDeadlineAt
        );
    }
}
