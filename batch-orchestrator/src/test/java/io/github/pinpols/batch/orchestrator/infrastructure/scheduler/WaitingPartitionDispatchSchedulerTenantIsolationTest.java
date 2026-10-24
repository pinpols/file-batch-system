package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceScheduler;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("WAITING 分片全局扫描的租户上下文隔离")
class WaitingPartitionDispatchSchedulerTenantIsolationTest {

  @Mock
  private ResourceScheduler resourceScheduler;

  @Mock
  private BatchOrchestratorGovernanceProperties governance;

  @Mock
  private JobPartitionMapper jobPartitionMapper;

  @Mock
  private JobTaskMapper jobTaskMapper;

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  @Mock
  private OrchestratorConfigCacheService configCacheService;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  @Mock
  private WaitingPartitionDispatcher dispatcher;

  @AfterEach
  void clearTenantContext() {
    RlsTenantContextHolder.clear();
  }

  @Test
  @DisplayName("全局候选扫描分别在所属租户上下文中评估并派发")
  void shouldEvaluateAndDispatchCandidatesWithinTheirOwnTenantContext() {
    JobPartitionEntity tenantAPartition = partition("tenant-a", 101L, 201L);
    JobPartitionEntity tenantBPartition = partition("tenant-b", 102L, 202L);
    JobTaskEntity tenantATask = task("tenant-a", 201L, 101L);
    JobTaskEntity tenantBTask = task("tenant-b", 202L, 102L);
    JobInstanceEntity tenantAInstance = instance("tenant-a", 201L);
    JobInstanceEntity tenantBInstance = instance("tenant-b", 202L);
    List<String> evaluatedTenants = new ArrayList<>();
    List<String> dispatchedTenants = new ArrayList<>();
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(governance.resourceScheduler()).thenReturn(new ResourceSchedulerProperties());
    when(jobPartitionMapper.selectWaitingPartitionsGlobal(100, PartitionStatus.WAITING.code()))
        .thenReturn(List.of(tenantAPartition, tenantBPartition));
    when(jobTaskMapper.selectByPartitionAndSeq("tenant-a", 101L, 1)).thenReturn(tenantATask);
    when(jobTaskMapper.selectByPartitionAndSeq("tenant-b", 102L, 1)).thenReturn(tenantBTask);
    when(jobInstanceMapper.selectById("tenant-a", 201L)).thenReturn(tenantAInstance);
    when(jobInstanceMapper.selectById("tenant-b", 202L)).thenReturn(tenantBInstance);
    when(configCacheService.findEnabledJobDefinition(any(), any())).thenReturn(null);
    doAnswer(invocation -> {
          ResourceSchedulingRequest request = invocation.getArgument(0);
          assertThat(RlsTenantContextHolder.get()).isEqualTo(request.getTenantId());
          evaluatedTenants.add(request.getTenantId());
          ResourceSchedulingDecision decision = new ResourceSchedulingDecision();
          decision.setDispatchable(true);
          return decision;
        })
        .when(resourceScheduler)
        .preview(any(ResourceSchedulingRequest.class));
    doAnswer(invocation -> {
          JobInstanceEntity instance = invocation.getArgument(2);
          assertThat(RlsTenantContextHolder.get()).isEqualTo(instance.getTenantId());
          dispatchedTenants.add(instance.getTenantId());
          return null;
        })
        .when(dispatcher)
        .executeDispatch(any(), any(), any(), any());

    WaitingPartitionDispatchScheduler scheduler = newScheduler();
    scheduler.dispatchWaitingPartitions();

    assertThat(evaluatedTenants).containsExactlyInAnyOrder("tenant-a", "tenant-b");
    assertThat(dispatchedTenants).containsExactlyInAnyOrder("tenant-a", "tenant-b");
    assertThat(RlsTenantContextHolder.get()).isNull();
    verify(jobTaskMapper).selectByPartitionAndSeq("tenant-a", 101L, 1);
    verify(jobTaskMapper).selectByPartitionAndSeq("tenant-b", 102L, 1);
  }

  private WaitingPartitionDispatchScheduler newScheduler() {
    OrchestratorJobMappers jobMappers = new OrchestratorJobMappers(
        jobInstanceMapper,
        jobPartitionMapper,
        jobTaskMapper,
        Mockito.mock(JobStepInstanceMapper.class),
        Mockito.mock(TriggerRequestMapper.class));
    return new WaitingPartitionDispatchScheduler(
        resourceScheduler,
        governance,
        jobMappers,
        configCacheService,
        gracefulShutdown,
        dispatcher);
  }

  private static JobPartitionEntity partition(String tenantId, Long id, Long jobInstanceId) {
    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setTenantId(tenantId);
    partition.setId(id);
    partition.setJobInstanceId(jobInstanceId);
    partition.setPartitionStatus(PartitionStatus.WAITING.code());
    return partition;
  }

  private static JobTaskEntity task(String tenantId, Long jobInstanceId, Long partitionId) {
    JobTaskEntity task = new JobTaskEntity();
    task.setTenantId(tenantId);
    task.setJobInstanceId(jobInstanceId);
    task.setJobPartitionId(partitionId);
    task.setTaskStatus(TaskStatus.CREATED.code());
    task.setTaskType("IMPORT");
    return task;
  }

  private static JobInstanceEntity instance(String tenantId, Long id) {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setTenantId(tenantId);
    instance.setId(id);
    instance.setJobCode("JOB-" + tenantId);
    instance.setInstanceStatus("WAITING");
    instance.setQueueCode("q-" + tenantId);
    instance.setWorkerGroup("group-" + tenantId);
    return instance;
  }
}
