package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import io.github.pinpols.batch.orchestrator.domain.query.WaitingPartitionScanCursor;
import io.github.pinpols.batch.orchestrator.domain.query.WaitingPartitionScanQuery;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

  @BeforeEach
  void seedSharedCursor() {
    AtomicReference<WaitingPartitionScanCursor> state =
        new AtomicReference<>(new WaitingPartitionScanCursor(0, 0, false, 0));
    lenient()
        .when(jobPartitionMapper.selectWaitingPartitionScanCursor())
        .thenAnswer(invocation -> state.get());
    lenient().when(jobPartitionMapper.selectLatestPartitionId()).thenReturn(102L);
    lenient()
        .when(jobPartitionMapper.advanceWaitingPartitionScanCursor(
            anyLong(), anyLong(), anyBoolean(), anyLong()))
        .thenAnswer(invocation -> {
          long next = invocation.getArgument(0);
          long upper = invocation.getArgument(1);
          boolean phase = invocation.getArgument(2);
          long version = invocation.getArgument(3);
          WaitingPartitionScanCursor current = state.get();
          if (current.version() != version) return 0;
          state.set(new WaitingPartitionScanCursor(next, upper, phase, version + 1));
          return 1;
        });
  }

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
    when(jobPartitionMapper.selectWaitingPartitionsGlobalInCycle(
            new WaitingPartitionScanQuery(100, PartitionStatus.WAITING.code(), 0, 102, false)))
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

  @Test
  @DisplayName("扫描到队尾后回绕，继续重试此前未能释放的分片")
  void shouldWrapAndRetryEarlierWaitingPartition() {
    JobPartitionEntity first = partition("tenant-a", 101L, 201L);
    JobPartitionEntity second = partition("tenant-b", 102L, 202L);
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setWaitingDispatchBatchSize(1);
    when(governance.resourceScheduler()).thenReturn(properties);
    when(jobPartitionMapper.selectWaitingPartitionsGlobalInCycle(
            new WaitingPartitionScanQuery(1, PartitionStatus.WAITING.code(), 0, 102, false)))
        .thenReturn(List.of(first));
    when(jobPartitionMapper.selectWaitingPartitionsGlobalInCycle(
            new WaitingPartitionScanQuery(1, PartitionStatus.WAITING.code(), 101L, 102L, false)))
        .thenReturn(List.of(second));
    WaitingPartitionDispatchScheduler scheduler = newScheduler();

    scheduler.dispatchWaitingPartitions();
    scheduler.dispatchWaitingPartitions();
    scheduler.dispatchWaitingPartitions();

    verify(jobTaskMapper, Mockito.times(2)).selectByPartitionAndSeq("tenant-a", 101L, 1);
    verify(jobTaskMapper).selectByPartitionAndSeq("tenant-b", 102L, 1);
  }

  @Test
  @DisplayName("扫描游标被其他调度器推进时，本轮不评估或派发旧候选")
  void shouldSkipCandidatesWhenCursorUpdateLosesRace() {
    JobPartitionEntity first = partition("tenant-a", 101L, 201L);
    when(governance.resourceScheduler()).thenReturn(new ResourceSchedulerProperties());
    when(jobPartitionMapper.selectWaitingPartitionsGlobalInCycle(
            new WaitingPartitionScanQuery(100, PartitionStatus.WAITING.code(), 0, 102, false)))
        .thenReturn(List.of(first));
    Mockito.doReturn(0)
        .when(jobPartitionMapper)
        .advanceWaitingPartitionScanCursor(101L, 102L, false, 0L);

    newScheduler().dispatchWaitingPartitions();

    verify(jobTaskMapper, never()).selectByPartitionAndSeq(any(), any(), Mockito.anyInt());
    verify(dispatcher, never()).executeDispatch(any(), any(), any(), any());
  }

  @Test
  @DisplayName("持续新增等待分片时仍能回绕并重新评估旧候选")
  void shouldRevisitOldWaitingPartitionWhileNewPartitionsKeepArriving() {
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setWaitingDispatchBatchSize(1);
    when(governance.resourceScheduler()).thenReturn(properties);
    List<JobPartitionEntity> queue = new ArrayList<>();
    queue.add(partition("tenant-a", 101L, 201L));
    queue.add(partition("tenant-b", 102L, 202L));
    when(jobPartitionMapper.selectLatestPartitionId())
        .thenAnswer(invocation -> queue.getLast().getId());
    when(jobPartitionMapper.selectWaitingPartitionsGlobalInCycle(any())).thenAnswer(invocation -> {
      WaitingPartitionScanQuery query = invocation.getArgument(0);
      return queue.stream()
          .filter(p -> p.getId() > query.afterId() && p.getId() <= query.upperId())
          .filter(p -> Boolean.TRUE.equals(p.getDryRun()) == query.dryRun())
          .limit(1)
          .toList();
    });
    WaitingPartitionDispatchScheduler scheduler = newScheduler();

    scheduler.dispatchWaitingPartitions();
    queue.add(partition("tenant-c", 103L, 203L));
    scheduler.dispatchWaitingPartitions();
    queue.add(partition("tenant-d", 104L, 204L));
    scheduler.dispatchWaitingPartitions();

    verify(jobTaskMapper, Mockito.times(2)).selectByPartitionAndSeq("tenant-a", 101L, 1);
  }

  @Test
  @DisplayName("正式分片位于后续批次时，应先于演练分片获得派发")
  void shouldPrioritizeFormalPartitionBeyondFirstBatch() {
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setWaitingDispatchBatchSize(1);
    when(governance.resourceScheduler()).thenReturn(properties);
    JobPartitionEntity dry = partition("dry-tenant", 101L, 201L);
    dry.setDryRun(true);
    JobPartitionEntity formal = partition("formal-tenant", 102L, 202L);
    formal.setDryRun(false);
    JobInstanceEntity dryInstance = instance("dry-tenant", 201L);
    dryInstance.setDryRun(true);
    JobInstanceEntity formalInstance = instance("formal-tenant", 202L);
    formalInstance.setDryRun(false);
    List<JobPartitionEntity> queue = List.of(dry, formal);
    lenient()
        .when(jobPartitionMapper.selectWaitingPartitionsGlobalInCycle(any()))
        .thenAnswer(invocation -> {
          WaitingPartitionScanQuery query = invocation.getArgument(0);
          return queue.stream()
              .filter(p -> p.getId() > query.afterId() && p.getId() <= query.upperId())
              .filter(p -> Boolean.TRUE.equals(p.getDryRun()) == query.dryRun())
              .limit(1)
              .toList();
        });
    lenient()
        .when(jobTaskMapper.selectByPartitionAndSeq("dry-tenant", 101L, 1))
        .thenReturn(task("dry-tenant", 201L, 101L));
    lenient()
        .when(jobTaskMapper.selectByPartitionAndSeq("formal-tenant", 102L, 1))
        .thenReturn(task("formal-tenant", 202L, 102L));
    lenient().when(jobInstanceMapper.selectById("dry-tenant", 201L)).thenReturn(dryInstance);
    lenient().when(jobInstanceMapper.selectById("formal-tenant", 202L)).thenReturn(formalInstance);
    when(resourceScheduler.preview(any())).thenAnswer(invocation -> {
      ResourceSchedulingDecision decision = new ResourceSchedulingDecision();
      decision.setDispatchable(true);
      return decision;
    });
    List<String> dispatched = new ArrayList<>();
    doAnswer(invocation -> {
          JobInstanceEntity job = invocation.getArgument(2);
          dispatched.add(job.getTenantId());
          return null;
        })
        .when(dispatcher)
        .executeDispatch(any(), any(), any(), any());

    newScheduler().dispatchWaitingPartitions();

    assertThat(dispatched).containsExactly("formal-tenant");
  }

  @Test
  @DisplayName("正式分片资源不可用时，后续阶段仍可评估演练分片")
  void shouldAllowDryRunAfterBlockedFormalCycle() {
    ResourceSchedulerProperties properties = new ResourceSchedulerProperties();
    properties.setWaitingDispatchBatchSize(1);
    when(governance.resourceScheduler()).thenReturn(properties);
    JobPartitionEntity dry = partition("dry-tenant", 101L, 201L);
    dry.setDryRun(true);
    JobPartitionEntity formal = partition("formal-tenant", 102L, 202L);
    JobInstanceEntity dryInstance = instance("dry-tenant", 201L);
    dryInstance.setDryRun(true);
    JobInstanceEntity formalInstance = instance("formal-tenant", 202L);
    List<JobPartitionEntity> queue = List.of(dry, formal);
    when(jobPartitionMapper.selectWaitingPartitionsGlobalInCycle(any())).thenAnswer(invocation -> {
      WaitingPartitionScanQuery query = invocation.getArgument(0);
      return queue.stream()
          .filter(p -> p.getId() > query.afterId() && p.getId() <= query.upperId())
          .filter(p -> Boolean.TRUE.equals(p.getDryRun()) == query.dryRun())
          .limit(1)
          .toList();
    });
    when(jobTaskMapper.selectByPartitionAndSeq("dry-tenant", 101L, 1))
        .thenReturn(task("dry-tenant", 201L, 101L));
    when(jobTaskMapper.selectByPartitionAndSeq("formal-tenant", 102L, 1))
        .thenReturn(task("formal-tenant", 202L, 102L));
    when(jobInstanceMapper.selectById("dry-tenant", 201L)).thenReturn(dryInstance);
    when(jobInstanceMapper.selectById("formal-tenant", 202L)).thenReturn(formalInstance);
    when(resourceScheduler.preview(any())).thenAnswer(invocation -> {
      ResourceSchedulingRequest request = invocation.getArgument(0);
      ResourceSchedulingDecision decision = new ResourceSchedulingDecision();
      decision.setDispatchable("dry-tenant".equals(request.getTenantId()));
      return decision;
    });
    List<String> dispatched = new ArrayList<>();
    doAnswer(invocation -> {
          JobInstanceEntity job = invocation.getArgument(2);
          dispatched.add(job.getTenantId());
          return null;
        })
        .when(dispatcher)
        .executeDispatch(any(), any(), any(), any());
    WaitingPartitionDispatchScheduler scheduler = newScheduler();

    scheduler.dispatchWaitingPartitions();
    scheduler.dispatchWaitingPartitions();

    assertThat(dispatched).containsExactly("dry-tenant");
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
