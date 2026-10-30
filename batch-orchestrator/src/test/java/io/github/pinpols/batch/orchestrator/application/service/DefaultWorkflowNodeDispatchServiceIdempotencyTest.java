package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceScheduler;
import io.github.pinpols.batch.orchestrator.application.service.task.ChildJobLaunchSupport;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionLifecycleService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskCreationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver;
import io.github.pinpols.batch.orchestrator.application.service.workflow.DefaultWorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodePayloadBuilder;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeTerminalFinalizer;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("流程节点派发幂等: 行锁防重与短路返回口径")
class DefaultWorkflowNodeDispatchServiceIdempotencyTest {

  @Mock
  JobInstanceMapper jobInstanceMapper;

  @Mock
  JobPartitionMapper jobPartitionMapper;

  @Mock
  JobTaskMapper jobTaskMapper;

  @Mock
  JobStepInstanceMapper jobStepInstanceMapper;

  @Mock
  TriggerRequestMapper triggerRequestMapper;

  @Mock
  WorkflowNodeMapper workflowNodeMapper;

  @Mock
  WorkflowRunMapper workflowRunMapper;

  @Mock
  BatchDayInstanceMapper batchDayInstanceMapper;

  @Mock
  WorkflowDefinitionMapper workflowDefinitionMapper;

  @Mock
  WorkflowNodeRunMapper workflowNodeRunMapper;

  @Mock
  SchedulePlanBuilder schedulePlanBuilder;

  @Mock
  PartitionLifecycleService partitionLifecycleService;

  @Mock
  TaskDispatchOutboxService taskDispatchOutboxService;

  @Mock
  WorkflowDagService workflowDagService;

  @Mock
  ResourceScheduler resourceScheduler;

  @Mock
  TaskCreationService taskCreationService;

  @Mock
  WorkflowNodePayloadBuilder payloadBuilder;

  @Mock
  ChildJobLaunchSupport childJobLaunchSupport;

  private DefaultWorkflowNodeDispatchService service;

  @BeforeEach
  void setUp() {
    OrchestratorJobMappers jobMappers = new OrchestratorJobMappers(
        jobInstanceMapper,
        jobPartitionMapper,
        jobTaskMapper,
        jobStepInstanceMapper,
        triggerRequestMapper);
    OrchestratorWorkflowMappers workflowMappers = new OrchestratorWorkflowMappers(
        workflowNodeMapper, workflowRunMapper, workflowNodeRunMapper);
    service = new DefaultWorkflowNodeDispatchService(
        jobMappers,
        workflowMappers,
        schedulePlanBuilder,
        partitionLifecycleService,
        taskDispatchOutboxService,
        workflowDagService,
        resourceScheduler,
        taskCreationService,
        payloadBuilder,
        childJobLaunchSupport,
        mock(CrossDayDependencyResolver.class),
        mock(WorkflowNodeTerminalFinalizer.class),
        mock(SecretPayloadProtector.class),
        mock(JobDependencyWaitingLaunchMapper.class),
        batchDayInstanceMapper,
        workflowDefinitionMapper);
    when(workflowDefinitionMapper.selectById(5L))
        .thenReturn(new WorkflowDefinitionEntity(
            5L, "t1", "FLOW", "Flow", "COMPOSITE", 1, true, null, "CAL"));
    when(workflowRunMapper.selectByTenantAndIdForUpdate("t1", 10L)).thenAnswer(invocation -> {
      WorkflowRunEntity current = new WorkflowRunEntity();
      current.setId(10L);
      current.setTenantId("t1");
      current.setWorkflowDefinitionId(5L);
      current.setBizDate(LocalDate.of(2026, 6, 29));
      current.setRunStatus("RUNNING");
      return current;
    });
  }

  @Test
  @DisplayName("节点已激活时用行锁查询直接短路返回零, 不再进行就绪判断")
  void dispatchNode_nodeAlreadyActive_usesSelectLatestForUpdate() {
    // C-3: isNodeAlreadyActivated 通过 selectLatestForUpdate 行锁防止 TOCTOU
    WorkflowNodeRunEntity existingRun = new WorkflowNodeRunEntity();
    existingRun.setId(1L);
    existingRun.setNodeCode("NODE_A");
    existingRun.setNodeStatus("RUNNING");

    when(workflowNodeRunMapper.selectLatestForUpdate(anyLong(), anyString()))
        .thenReturn(existingRun);

    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setId(100L);
    jobInstance.setTenantId("t1");

    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setId(10L);
    workflowRun.setTenantId("t1");
    workflowRun.setWorkflowDefinitionId(5L);
    workflowRun.setBizDate(LocalDate.of(2026, 6, 29));
    workflowRun.setRunStatus("RUNNING");

    WorkflowDagService.DagNodeResolution node =
        new WorkflowDagService.DagNodeResolution("NODE_A", "TASK");

    // P1-4 after-fix：isNodeAlreadyActivated (FOR UPDATE) 现在跑在 readiness 检查之前。
    // 节点已激活时直接短路返回 0，不会再触达 isNodeReadyForDispatch / selectByWorkflowDefinitionIdAndNodeCode。
    int result = service.dispatchNode(jobInstance, workflowRun, node, "{}", "trace-1");

    verify(workflowNodeRunMapper).selectLatestForUpdate(10L, "NODE_A");
    assertThat(result).isZero();
  }
}
