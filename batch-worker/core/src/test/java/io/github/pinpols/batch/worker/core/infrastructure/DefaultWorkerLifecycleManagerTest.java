package io.github.pinpols.batch.worker.core.infrastructure;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import io.github.pinpols.batch.worker.core.support.WorkerSelfRegistrationService;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Worker 生命周期管理器: 下线流程的本地清理与远端同步容错")
class DefaultWorkerLifecycleManagerTest {

  @Mock
  private WorkerSelfRegistrationService workerRegistryService;

  @Mock
  private WorkerRuntimeState workerRuntimeState;

  @Mock
  private ActiveTaskLeaseRegistry activeTaskLeaseRegistry;

  @Test
  @DisplayName("下线时即使远端状态同步失败, 本地运行时注册信息也必须被移除")
  void shutdown_removesLocalStateEvenWhenRemoteStatusSyncFails() {
    WorkerRegistration registration = new WorkerRegistration();
    registration.setWorkerId("worker-1");
    when(workerRuntimeState.get("worker-1")).thenReturn(registration);
    when(activeTaskLeaseRegistry.snapshot()).thenReturn(List.of());
    when(workerRegistryService.updateStatus(registration, "DRAINING")).thenReturn(registration);
    doThrow(new RuntimeException("orchestrator unavailable"))
        .when(workerRegistryService)
        .updateStatus(registration, "DECOMMISSIONED");

    DefaultWorkerLifecycleManager manager = new DefaultWorkerLifecycleManager(
        workerRegistryService,
        workerRuntimeState,
        activeTaskLeaseRegistry,
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties())));

    manager.shutdown("worker-1");

    verify(workerRuntimeState).remove("worker-1");
  }
}
