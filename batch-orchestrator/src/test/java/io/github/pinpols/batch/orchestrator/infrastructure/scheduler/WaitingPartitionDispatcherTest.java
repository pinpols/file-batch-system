package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import io.github.pinpols.batch.orchestrator.application.scheduler.GlobalJobAdmission;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionLifecycleService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("等待中分区的派发推进:限流与容量准入全部通过后才释放分区并写入派发事件")
class WaitingPartitionDispatcherTest {

  private final JobInstanceMapper jobInstanceMapper =
      org.mockito.Mockito.mock(JobInstanceMapper.class);
  private final WorkflowRunMapper workflowRunMapper =
      org.mockito.Mockito.mock(WorkflowRunMapper.class);
  private final TaskDispatchOutboxService taskDispatchOutboxService =
      org.mockito.Mockito.mock(TaskDispatchOutboxService.class);
  private final PartitionLifecycleService partitionLifecycleService =
      org.mockito.Mockito.mock(PartitionLifecycleService.class);
  private final TenantActionRateLimiter tenantActionRateLimiter =
      org.mockito.Mockito.mock(TenantActionRateLimiter.class);
  private final OrchestratorConfigCacheService configCacheService =
      org.mockito.Mockito.mock(OrchestratorConfigCacheService.class);
  private final FairShareGroupAdmissionGuard fairShareGroupAdmissionGuard =
      org.mockito.Mockito.mock(FairShareGroupAdmissionGuard.class);
  private final GlobalJobAdmission globalJobAdmission =
      org.mockito.Mockito.mock(GlobalJobAdmission.class);

  private WaitingPartitionDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    OrchestratorJobMappers jobMappers = new OrchestratorJobMappers(
        jobInstanceMapper,
        org.mockito.Mockito.mock(JobPartitionMapper.class),
        org.mockito.Mockito.mock(JobTaskMapper.class),
        org.mockito.Mockito.mock(JobStepInstanceMapper.class),
        org.mockito.Mockito.mock(TriggerRequestMapper.class));
    OrchestratorWorkflowMappers workflowMappers = new OrchestratorWorkflowMappers(
        org.mockito.Mockito.mock(WorkflowNodeMapper.class),
        workflowRunMapper,
        org.mockito.Mockito.mock(WorkflowNodeRunMapper.class));
    dispatcher = new WaitingPartitionDispatcher(
        jobMappers,
        workflowMappers,
        taskDispatchOutboxService,
        partitionLifecycleService,
        tenantActionRateLimiter,
        configCacheService,
        globalJobAdmission,
        fairShareGroupAdmissionGuard);
  }

  @Test
  @DisplayName("租户派发速率额度耗尽时提前返回,不释放分区,不写入派发事件,也不把实例置为运行中")
  void shouldStopBeforeStateMutation_whenTenantDispatchLimitExhausted() {
    JobInstanceEntity instance = waitingInstance();
    when(jobInstanceMapper.selectById("tenant-a", 7L)).thenReturn(instance);
    when(tenantActionRateLimiter.tryConsume(eq("tenant-a"), any())).thenReturn(false);

    dispatcher.executeDispatch(partition(), task(), instance, dispatchDecision());

    verify(partitionLifecycleService, never()).releaseForDispatch(any(), any(), any(), any());
    verify(taskDispatchOutboxService, never())
        .writeDispatchEvent(any(), any(), any(), any(), any());
    verify(jobInstanceMapper, never()).markRunning(any());
  }

  @Test
  @DisplayName("分区释放成功后写入派发事件,按租户回填实例运行参数,并把关联工作流运行推进为运行中")
  void shouldWriteOutboxAndAdvanceParentStates_afterPartitionRelease() {
    JobInstanceEntity instance = waitingInstance();
    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setTenantId("tenant-a");
    workflowRun.setId(99L);
    workflowRun.setRunStatus(WorkflowRunStatus.CREATED.code());
    workflowRun.setCurrentNodeCode("export");
    when(jobInstanceMapper.selectById("tenant-a", 7L)).thenReturn(instance);
    when(tenantActionRateLimiter.tryConsume(eq("tenant-a"), any())).thenReturn(true);
    when(globalJobAdmission.hasCapacity()).thenReturn(true);
    when(fairShareGroupAdmissionGuard.hasCapacity(any())).thenReturn(true);
    when(partitionLifecycleService.releaseForDispatch(any(), any(), any(), any()))
        .thenReturn(true);
    when(jobInstanceMapper.markRunning(any())).thenReturn(1);
    when(workflowRunMapper.selectByRelatedJobInstanceId("tenant-a", 7L)).thenReturn(workflowRun);

    dispatcher.executeDispatch(partition(), task(), instance, dispatchDecision());

    verify(taskDispatchOutboxService).writeDispatchEvent(eq(instance), any(), any(), any(), any());
    ArgumentCaptor<io.github.pinpols.batch.orchestrator.domain.param.MarkInstanceRunningParam>
        runningParam = ArgumentCaptor.forClass(
            io.github.pinpols.batch.orchestrator.domain.param.MarkInstanceRunningParam.class);
    verify(jobInstanceMapper).markRunning(runningParam.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "tenant-a", runningParam.getValue().getTenantId());
    org.junit.jupiter.api.Assertions.assertEquals(1L, instance.getVersion());
    verify(workflowRunMapper)
        .markRunning(
            eq("tenant-a"), eq(99L), eq(WorkflowRunStatus.RUNNING.code()), eq("export"), any());
  }

  @Test
  @DisplayName("公平份额分组容量不足时拒绝释放分区,也不写入派发事件")
  void shouldNotRelease_whenFairShareGroupHasNoCapacity() {
    JobInstanceEntity instance = waitingInstance();
    when(jobInstanceMapper.selectById("tenant-a", 7L)).thenReturn(instance);
    when(tenantActionRateLimiter.tryConsume(eq("tenant-a"), any())).thenReturn(true);
    when(globalJobAdmission.hasCapacity()).thenReturn(true);
    TenantQuotaPolicyEntity quotaPolicy = new TenantQuotaPolicyEntity(
        1L, "tenant-a", "p", 0, 0, 0, 1, "settlement", 0, 0, "NONE", 1, true, "QUEUE_DEFER");
    when(configCacheService.findEnabledQuotaPolicy("tenant-a")).thenReturn(quotaPolicy);
    when(fairShareGroupAdmissionGuard.hasCapacity(quotaPolicy)).thenReturn(false);

    dispatcher.executeDispatch(partition(), task(), instance, dispatchDecision());

    verify(partitionLifecycleService, never()).releaseForDispatch(any(), any(), any(), any());
    verify(taskDispatchOutboxService, never())
        .writeDispatchEvent(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("全局容量已满时直接放弃本次派发,不再继续公平份额容量判定")
  void shouldNotReleaseWaitingJob_whenGlobalCapacityFull() {
    JobInstanceEntity instance = waitingInstance();
    when(jobInstanceMapper.selectById("tenant-a", 7L)).thenReturn(instance);
    when(tenantActionRateLimiter.tryConsume(eq("tenant-a"), any())).thenReturn(true);
    when(globalJobAdmission.hasCapacity()).thenReturn(false);

    dispatcher.executeDispatch(partition(), task(), instance, dispatchDecision());

    verify(partitionLifecycleService, never()).releaseForDispatch(any(), any(), any(), any());
    verify(fairShareGroupAdmissionGuard, never()).hasCapacity(any());
  }

  @Test
  @DisplayName("数据库中的父实例已处于运行中时沿用最新快照,不重复占用全局容量,也不重复置为运行中")
  void shouldUseCurrentRunningParentAndSkipGlobalAdmission_whenSnapshotOutdated() {
    JobInstanceEntity staleWaitingSnapshot = waitingInstance();
    JobInstanceEntity currentRunning = waitingInstance();
    currentRunning.setInstanceStatus(JobInstanceStatus.RUNNING.code());
    currentRunning.setVersion(1L);
    when(jobInstanceMapper.selectById("tenant-a", 7L)).thenReturn(currentRunning);
    when(tenantActionRateLimiter.tryConsume(eq("tenant-a"), any())).thenReturn(true);
    when(fairShareGroupAdmissionGuard.hasCapacity(any())).thenReturn(true);
    when(partitionLifecycleService.releaseForDispatch(any(), any(), any(), any()))
        .thenReturn(true);

    dispatcher.executeDispatch(partition(), task(), staleWaitingSnapshot, dispatchDecision());

    verify(globalJobAdmission, never()).hasCapacity();
    verify(jobInstanceMapper, never()).markRunning(any());
    verify(taskDispatchOutboxService)
        .writeDispatchEvent(eq(currentRunning), any(), any(), any(), any());
  }

  @Test
  @DisplayName("数据库中的父实例已是取消终态时立即放弃,不消费租户速率额度,也不释放分区")
  void shouldNotRelease_whenCurrentParentTerminal() {
    JobInstanceEntity staleWaitingSnapshot = waitingInstance();
    JobInstanceEntity cancelled = waitingInstance();
    cancelled.setInstanceStatus(JobInstanceStatus.CANCELLED.code());
    when(jobInstanceMapper.selectById("tenant-a", 7L)).thenReturn(cancelled);

    dispatcher.executeDispatch(partition(), task(), staleWaitingSnapshot, dispatchDecision());

    verify(tenantActionRateLimiter, never()).tryConsume(any(), any());
    verify(partitionLifecycleService, never()).releaseForDispatch(any(), any(), any(), any());
  }

  private static JobInstanceEntity waitingInstance() {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setTenantId("tenant-a");
    instance.setId(7L);
    instance.setTraceId("trace-7");
    instance.setInstanceStatus(JobInstanceStatus.WAITING.code());
    instance.setExpectedPartitionCount(1);
    instance.setVersion(0L);
    return instance;
  }

  private static JobPartitionEntity partition() {
    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setTenantId("tenant-a");
    partition.setId(11L);
    return partition;
  }

  private static JobTaskEntity task() {
    JobTaskEntity task = new JobTaskEntity();
    task.setTenantId("tenant-a");
    task.setId(13L);
    return task;
  }

  private static ResourceSchedulingDecision dispatchDecision() {
    ResourceSchedulingDecision decision = new ResourceSchedulingDecision();
    decision.setFairnessScore(12L);
    decision.setTenantWeight(1);
    decision.setQueueWeight(1);
    return decision;
  }
}
