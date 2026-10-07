package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.governance.WorkerDrainGovernanceService;
import io.github.pinpols.batch.orchestrator.config.WorkerDrainProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("工作节点排空超时调度,验证开关关闭时跳过扫描与仅对超期节点执行接管")
class WorkerDrainTimeoutSchedulerTest {

  @Mock
  private WorkerRegistryMapper workerRegistryMapper;

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
        workerRegistryMapper,
        workerDrainGovernanceService,
        workerDrainProperties,
        gracefulShutdown);
  }

  @Test
  @DisplayName("排空超时调度开关关闭时不扫描排空节点,也不执行超时接管")
  void shouldSkipWhenDisabled() {
    workerDrainProperties.setEnabled(false);

    scheduler.expireDrains();

    verify(workerRegistryMapper, never()).selectByStatus(WorkerRegistryStatus.DRAINING.code());
    verify(workerDrainGovernanceService, never())
        .takeoverAfterDrainTimeout(anyString(), anyString());
  }

  @Test
  @DisplayName("仅对排空截止时间已过期的节点执行超时接管,截止时间未到或缺失的节点保持原状")
  void shouldTakeOverOnlyExpiredDrainingWorkers() {
    Instant now = BatchDateTimeSupport.utcNow();
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
