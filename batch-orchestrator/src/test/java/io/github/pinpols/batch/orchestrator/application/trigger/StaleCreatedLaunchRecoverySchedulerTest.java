package io.github.pinpols.batch.orchestrator.application.trigger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionDispatchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class StaleCreatedLaunchRecoverySchedulerTest {

  @Test
  void recoversStructurallyEmptyCreatedInstanceWhenRequestWasAlreadyMarkedLaunched() {
    JobInstanceMapper jobInstanceMapper = mock(JobInstanceMapper.class);
    TriggerRequestMapper triggerRequestMapper = mock(TriggerRequestMapper.class);
    PartitionDispatchService partitionDispatchService = mock(PartitionDispatchService.class);
    OrchestratorGracefulShutdown gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    StaleCreatedLaunchRecoveryScheduler scheduler = new StaleCreatedLaunchRecoveryScheduler(
        jobInstanceMapper,
        triggerRequestMapper,
        partitionDispatchService,
        gracefulShutdown,
        new SimpleMeterRegistry());

    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(101L);
    instance.setTenantId("tenant-a");
    instance.setTriggerRequestId(202L);
    instance.setJobCode("atomic_sql_demo");
    instance.setTriggerType("API");
    instance.setParamsSnapshot("{\"effectiveParams\":{\"taskType\":\"sql\"}}");
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setId(202L);
    request.setTenantId("tenant-a");
    request.setRequestId("request-a");
    request.setRequestStatus(TriggerRequestStatus.LAUNCHED.code());

    when(jobInstanceMapper.selectStaleCreatedLaunchCandidates(any(), anyInt()))
        .thenReturn(List.of(instance));
    when(triggerRequestMapper.selectById("tenant-a", 202L)).thenReturn(request);

    scheduler.recover();

    verify(partitionDispatchService).dispatch(any());
  }

  @Test
  void recoversLegacyDuplicateOnlyWhenRequestCreatedTheInstance() {
    JobInstanceMapper jobInstanceMapper = mock(JobInstanceMapper.class);
    TriggerRequestMapper triggerRequestMapper = mock(TriggerRequestMapper.class);
    PartitionDispatchService partitionDispatchService = mock(PartitionDispatchService.class);
    OrchestratorGracefulShutdown gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    StaleCreatedLaunchRecoveryScheduler scheduler = new StaleCreatedLaunchRecoveryScheduler(
        jobInstanceMapper,
        triggerRequestMapper,
        partitionDispatchService,
        gracefulShutdown,
        new SimpleMeterRegistry());

    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(101L);
    instance.setTenantId("tenant-a");
    instance.setTriggerRequestId(202L);
    instance.setJobCode("atomic_sql_demo");
    instance.setTriggerType("API");
    instance.setParamsSnapshot("{\"effectiveParams\":{\"taskType\":\"sql\"}}");
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setId(202L);
    request.setTenantId("tenant-a");
    request.setRequestId("request-a");
    request.setRequestStatus(TriggerRequestStatus.DUPLICATE.code());

    when(jobInstanceMapper.selectStaleCreatedLaunchCandidates(any(), anyInt()))
        .thenReturn(List.of(instance));
    when(triggerRequestMapper.selectById("tenant-a", 202L)).thenReturn(request);

    scheduler.recover();

    verify(partitionDispatchService).dispatch(any());
    verify(triggerRequestMapper).reconcileRecoveredCreatedLaunch("tenant-a", "request-a", 101L);
  }
}
