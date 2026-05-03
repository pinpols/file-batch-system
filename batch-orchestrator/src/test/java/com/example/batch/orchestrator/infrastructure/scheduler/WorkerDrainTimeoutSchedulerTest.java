package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.application.service.governance.WorkerDrainGovernanceService;
import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import com.example.batch.orchestrator.domain.value.JsonbString;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkerDrainTimeoutSchedulerTest {

  @Mock private WorkerRegistryMapper workerRegistryMapper;

  @Mock private WorkerDrainGovernanceService workerDrainGovernanceService;

  @Mock private OrchestratorGracefulShutdown gracefulShutdown;

  private WorkerDrainProperties workerDrainProperties;
  private WorkerDrainTimeoutScheduler scheduler;

  @BeforeEach
  void setUp() {
    workerDrainProperties = new WorkerDrainProperties();
    workerDrainProperties.setEnabled(true);
    scheduler =
        new WorkerDrainTimeoutScheduler(
            workerRegistryMapper,
            workerDrainGovernanceService,
            workerDrainProperties,
            gracefulShutdown);
  }

  @Test
  void shouldSkipWhenDisabled() {
    workerDrainProperties.setEnabled(false);

    scheduler.expireDrains();

    verify(workerRegistryMapper, never()).selectByStatus(WorkerRegistryStatus.DRAINING.code());
    verify(workerDrainGovernanceService, never())
        .takeoverAfterDrainTimeout(anyString(), anyString());
  }

  @Test
  void shouldTakeOverOnlyExpiredDrainingWorkers() {
    Instant now = Instant.now();
    WorkerRegistryEntity expired =
        worker("t1", "worker-expired", now.minusSeconds(120), now.minusSeconds(1));
    WorkerRegistryEntity future =
        worker("t1", "worker-future", now.minusSeconds(120), now.plusSeconds(60));
    WorkerRegistryEntity missingDeadline =
        worker("t1", "worker-missing", now.minusSeconds(120), null);

    when(workerRegistryMapper.selectByStatus(WorkerRegistryStatus.DRAINING.code()))
        .thenReturn(Arrays.asList(expired, future, missingDeadline, null));

    scheduler.expireDrains();

    verify(workerDrainGovernanceService).takeoverAfterDrainTimeout("t1", "worker-expired");
    verify(workerDrainGovernanceService, never()).takeoverAfterDrainTimeout("t1", "worker-future");
    verify(workerDrainGovernanceService, never()).takeoverAfterDrainTimeout("t1", "worker-missing");
  }

  private static WorkerRegistryEntity worker(
      String tenantId, String workerCode, Instant heartbeatAt, Instant drainDeadlineAt) {
    return new WorkerRegistryEntity(
        1L,
        tenantId,
        workerCode,
        "WG-1",
        JsonbString.of("{}"),
        null,
        WorkerRegistryStatus.DRAINING.code(),
        heartbeatAt,
        3,
        10,
        heartbeatAt.minusSeconds(30),
        drainDeadlineAt);
  }
}
