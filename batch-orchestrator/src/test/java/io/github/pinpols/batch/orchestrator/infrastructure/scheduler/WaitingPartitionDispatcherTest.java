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
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionLifecycleService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        tenantActionRateLimiter);
  }

  @Test
  void stopsBeforeStateMutationWhenTenantDispatchLimitIsExhausted() {
    JobInstanceEntity instance = waitingInstance();
    when(tenantActionRateLimiter.tryConsume(eq("tenant-a"), any())).thenReturn(false);

    dispatcher.executeDispatch(partition(), task(), instance, dispatchDecision());

    verify(partitionLifecycleService, never()).releaseForDispatch(any(), any(), any(), any());
    verify(taskDispatchOutboxService, never())
        .writeDispatchEvent(any(), any(), any(), any(), any());
    verify(jobInstanceMapper, never()).markRunning(any());
  }

  @Test
  void writesOutboxAndAdvancesParentStatesOnlyAfterPartitionRelease() {
    JobInstanceEntity instance = waitingInstance();
    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setTenantId("tenant-a");
    workflowRun.setId(99L);
    workflowRun.setRunStatus(WorkflowRunStatus.CREATED.code());
    workflowRun.setCurrentNodeCode("export");
    when(tenantActionRateLimiter.tryConsume(eq("tenant-a"), any())).thenReturn(true);
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
