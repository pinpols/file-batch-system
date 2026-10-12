package io.github.pinpols.batch.orchestrator.application.trigger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionDispatchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("滞留创建态启动恢复调度: 结构空实例与历史重复实例的恢复口径")
class StaleCreatedLaunchRecoverySchedulerTest {

  @Test
  @DisplayName("请求已标记启动而实例结构为空时触发恢复派发")
  void shouldRecover_whenCreatedInstanceEmptyAndRequestMarkedLaunched() {
    JobInstanceMapper jobInstanceMapper = mock(JobInstanceMapper.class);
    TriggerRequestMapper triggerRequestMapper = mock(TriggerRequestMapper.class);
    PartitionDispatchService partitionDispatchService = mock(PartitionDispatchService.class);
    LaunchService launchService = mock(LaunchService.class);
    OrchestratorGracefulShutdown gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    StaleCreatedLaunchRecoveryScheduler scheduler = new StaleCreatedLaunchRecoveryScheduler(
        jobInstanceMapper,
        triggerRequestMapper,
        partitionDispatchService,
        launchService,
        gracefulShutdown,
        new SimpleMeterRegistry(),
        new StaleCreatedLaunchRecoveryProperties());

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
  @DisplayName("仅当请求确实创建了该实例时才恢复历史重复实例并回写对账")
  void shouldRecoverLegacyDuplicate_whenRequestCreatedInstance() {
    JobInstanceMapper jobInstanceMapper = mock(JobInstanceMapper.class);
    TriggerRequestMapper triggerRequestMapper = mock(TriggerRequestMapper.class);
    PartitionDispatchService partitionDispatchService = mock(PartitionDispatchService.class);
    LaunchService launchService = mock(LaunchService.class);
    OrchestratorGracefulShutdown gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    StaleCreatedLaunchRecoveryScheduler scheduler = new StaleCreatedLaunchRecoveryScheduler(
        jobInstanceMapper,
        triggerRequestMapper,
        partitionDispatchService,
        launchService,
        gracefulShutdown,
        new SimpleMeterRegistry(),
        new StaleCreatedLaunchRecoveryProperties());

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

  @Test
  @DisplayName("历史束作业缺少绑定时置为业务失败，不进入周期重试")
  void shouldRejectPermanentBundleFailureInsteadOfRetryingForever() {
    JobInstanceMapper jobInstanceMapper = mock(JobInstanceMapper.class);
    TriggerRequestMapper triggerRequestMapper = mock(TriggerRequestMapper.class);
    PartitionDispatchService partitionDispatchService = mock(PartitionDispatchService.class);
    LaunchService launchService = mock(LaunchService.class);
    OrchestratorGracefulShutdown gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    StaleCreatedLaunchRecoveryScheduler scheduler = new StaleCreatedLaunchRecoveryScheduler(
        jobInstanceMapper,
        triggerRequestMapper,
        partitionDispatchService,
        launchService,
        gracefulShutdown,
        new SimpleMeterRegistry(),
        new StaleCreatedLaunchRecoveryProperties());

    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(101L);
    instance.setTenantId("tenant-a");
    instance.setTriggerRequestId(202L);
    instance.setJobCode("bundle-dispatch");
    instance.setTriggerType("API");
    instance.setParamsSnapshot("{\"effectiveParams\":{}}");
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setId(202L);
    request.setTenantId("tenant-a");
    request.setRequestId("request-a");
    request.setRequestStatus(TriggerRequestStatus.ACCEPTED.code());
    when(jobInstanceMapper.selectStaleCreatedLaunchCandidates(any(), anyInt()))
        .thenReturn(List.of(instance));
    when(triggerRequestMapper.selectById("tenant-a", 202L)).thenReturn(request);
    doThrow(BizException.of(
            ResultCode.BUSINESS_ERROR,
            "error.partition.dispatch_business_error",
            "ADR-046_BUNDLE_INVALID",
            "bundleFiles missing"))
        .when(partitionDispatchService)
        .dispatch(any());

    scheduler.recover();

    verify(launchService)
        .finalizeDispatchFailure(any(), org.mockito.ArgumentMatchers.same(instance), any());
  }
}
