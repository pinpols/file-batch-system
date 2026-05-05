package com.example.batch.worker.core.infrastructure;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultWorkerLifecycleManagerTest {

  @Mock private WorkerSelfRegistrationService workerRegistryService;

  @Mock private WorkerRuntimeState workerRuntimeState;

  @Mock private ActiveTaskLeaseRegistry activeTaskLeaseRegistry;

  @Test
  void shutdown_removesLocalStateEvenWhenRemoteStatusSyncFails() {
    WorkerRegistration registration = new WorkerRegistration();
    registration.setWorkerId("worker-1");
    when(workerRuntimeState.get("worker-1")).thenReturn(registration);
    when(activeTaskLeaseRegistry.snapshot()).thenReturn(List.of());
    when(workerRegistryService.updateStatus(registration, "DRAINING")).thenReturn(registration);
    doThrow(new RuntimeException("orchestrator unavailable"))
        .when(workerRegistryService)
        .updateStatus(registration, "DECOMMISSIONED");

    DefaultWorkerLifecycleManager manager =
        new DefaultWorkerLifecycleManager(
            workerRegistryService,
            workerRuntimeState,
            activeTaskLeaseRegistry,
            new BatchDateTimeSupport(
                Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties())));

    manager.shutdown("worker-1");

    verify(workerRuntimeState).remove("worker-1");
  }
}
