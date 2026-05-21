package com.example.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.plan.SchedulePlanBuilder;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.application.service.task.ChildJobLaunchSupport;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.task.PartitionLifecycleService;
import com.example.batch.orchestrator.application.service.task.TaskExecutionService;
import com.example.batch.orchestrator.application.service.workflow.WorkflowDagService.DagNodeResolution;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 守护 DAG 节点派发的早退分支(避免重复激活):
 *
 * <ul>
 *   <li>任意 null 入参 → 返 0,不读 DB
 *   <li>节点已激活(READY/RUNNING/SUCCESS)→ 返 0,不再走 DAG ready 检查
 *   <li>DAG readiness 不满足 → 返 0
 *   <li>workflow_node 不存在 → 返 0
 * </ul>
 *
 * <p>gateway / JOB / task 三条主路径涉及众多 collaborator,留集成测覆盖。
 */
@ExtendWith(MockitoExtension.class)
class DefaultWorkflowNodeDispatchServiceTest {

  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private JobPartitionMapper jobPartitionMapper;
  @Mock private JobTaskMapper jobTaskMapper;
  @Mock private JobStepInstanceMapper jobStepInstanceMapper;
  @Mock private TriggerRequestMapper triggerRequestMapper;
  @Mock private WorkflowNodeMapper workflowNodeMapper;
  @Mock private WorkflowRunMapper workflowRunMapper;
  @Mock private WorkflowNodeRunMapper workflowNodeRunMapper;

  @Mock private SchedulePlanBuilder schedulePlanBuilder;
  @Mock private PartitionLifecycleService partitionLifecycleService;
  @Mock private TaskDispatchOutboxService taskDispatchOutboxService;
  @Mock private WorkflowDagService workflowDagService;
  @Mock private ResourceScheduler resourceScheduler;
  @Mock private ObjectProvider<TaskExecutionService> taskExecutionServiceProvider;
  @Mock private WorkflowNodePayloadBuilder payloadBuilder;
  @Mock private ChildJobLaunchSupport childJobLaunchSupport;
  @Mock private CrossDayDependencyResolver crossDayDependencyResolver;

  private DefaultWorkflowNodeDispatchService service;

  @BeforeEach
  void setUp() {
    OrchestratorJobMappers jobMappers =
        new OrchestratorJobMappers(
            jobInstanceMapper,
            jobPartitionMapper,
            jobTaskMapper,
            jobStepInstanceMapper,
            triggerRequestMapper);
    OrchestratorWorkflowMappers workflowMappers =
        new OrchestratorWorkflowMappers(
            workflowNodeMapper, workflowRunMapper, workflowNodeRunMapper);
    service =
        new DefaultWorkflowNodeDispatchService(
            jobMappers,
            workflowMappers,
            schedulePlanBuilder,
            partitionLifecycleService,
            taskDispatchOutboxService,
            workflowDagService,
            resourceScheduler,
            taskExecutionServiceProvider,
            payloadBuilder,
            childJobLaunchSupport,
            crossDayDependencyResolver);
  }

  private JobInstanceEntity instance() {
    JobInstanceEntity j = new JobInstanceEntity();
    j.setId(100L);
    j.setTenantId("ta");
    return j;
  }

  private WorkflowRunEntity workflowRun() {
    WorkflowRunEntity r = new WorkflowRunEntity();
    r.setId(10L);
    r.setWorkflowDefinitionId(50L);
    r.setTenantId("ta");
    return r;
  }

  // ===== null inputs =====

  @Test
  @DisplayName("null jobInstance → 返 0,不读 DB")
  void nullJobInstanceReturnsZero() {
    assertThat(
            service.dispatchNode(
                null, workflowRun(), new DagNodeResolution("n1", "TASK"), null, "trace"))
        .isZero();
    verify(workflowNodeRunMapper, never()).selectLatestForUpdate(anyLong(), anyString());
  }

  @Test
  @DisplayName("null workflowRun → 返 0")
  void nullWorkflowRunReturnsZero() {
    assertThat(
            service.dispatchNode(
                instance(), null, new DagNodeResolution("n1", "TASK"), null, "trace"))
        .isZero();
  }

  @Test
  @DisplayName("null node → 返 0")
  void nullNodeReturnsZero() {
    assertThat(service.dispatchNode(instance(), workflowRun(), null, null, "trace")).isZero();
  }

  // ===== 已激活早退 =====

  @Test
  @DisplayName("节点已 READY → 返 0,不走 DAG ready check")
  void alreadyReadyReturnsZero() {
    WorkflowNodeRunEntity existing = new WorkflowNodeRunEntity();
    existing.setNodeStatus(WorkflowNodeRunStatus.READY.code());
    when(workflowNodeRunMapper.selectLatestForUpdate(eq(10L), eq("n1"))).thenReturn(existing);

    int result =
        service.dispatchNode(
            instance(), workflowRun(), new DagNodeResolution("n1", "TASK"), null, "trace");
    assertThat(result).isZero();
    verify(workflowDagService, never())
        .isNodeReadyForDispatch(anyLong(), anyLong(), anyString(), any());
  }

  @Test
  @DisplayName("节点已 RUNNING → 返 0")
  void alreadyRunningReturnsZero() {
    WorkflowNodeRunEntity existing = new WorkflowNodeRunEntity();
    existing.setNodeStatus(WorkflowNodeRunStatus.RUNNING.code());
    when(workflowNodeRunMapper.selectLatestForUpdate(eq(10L), eq("n1"))).thenReturn(existing);

    assertThat(
            service.dispatchNode(
                instance(), workflowRun(), new DagNodeResolution("n1", "TASK"), null, "trace"))
        .isZero();
  }

  @Test
  @DisplayName("节点已 SUCCESS → 返 0(终态去重)")
  void alreadySuccessReturnsZero() {
    WorkflowNodeRunEntity existing = new WorkflowNodeRunEntity();
    existing.setNodeStatus(WorkflowNodeRunStatus.SUCCESS.code());
    when(workflowNodeRunMapper.selectLatestForUpdate(eq(10L), eq("n1"))).thenReturn(existing);

    assertThat(
            service.dispatchNode(
                instance(), workflowRun(), new DagNodeResolution("n1", "TASK"), null, "trace"))
        .isZero();
  }

  @Test
  @DisplayName("节点已 FAILED → 不视为 active,继续走 ready check(允许重试)")
  void alreadyFailedProceedsToDagCheck() {
    WorkflowNodeRunEntity existing = new WorkflowNodeRunEntity();
    existing.setNodeStatus(WorkflowNodeRunStatus.FAILED.code());
    when(workflowNodeRunMapper.selectLatestForUpdate(eq(10L), eq("n1"))).thenReturn(existing);
    when(workflowDagService.isNodeReadyForDispatch(anyLong(), anyLong(), anyString(), any()))
        .thenReturn(false);

    assertThat(
            service.dispatchNode(
                instance(), workflowRun(), new DagNodeResolution("n1", "TASK"), null, "trace"))
        .isZero();
    verify(workflowDagService).isNodeReadyForDispatch(eq(10L), eq(50L), eq("n1"), any());
  }

  // ===== DAG ready check =====

  @Test
  @DisplayName("DAG readiness=false → 返 0,不读 workflow_node")
  void notReadyReturnsZero() {
    when(workflowNodeRunMapper.selectLatestForUpdate(anyLong(), anyString())).thenReturn(null);
    when(workflowDagService.isNodeReadyForDispatch(anyLong(), anyLong(), anyString(), any()))
        .thenReturn(false);

    assertThat(
            service.dispatchNode(
                instance(), workflowRun(), new DagNodeResolution("n1", "TASK"), null, "trace"))
        .isZero();
    verify(workflowNodeMapper, never())
        .selectByWorkflowDefinitionIdAndNodeCode(anyLong(), anyString());
  }

  // ===== workflow_node 缺失 =====

  @Test
  @DisplayName("workflow_node 不存在 → 返 0,不调任何 dispatch 路径")
  void missingWorkflowNodeReturnsZero() {
    when(workflowNodeRunMapper.selectLatestForUpdate(anyLong(), anyString())).thenReturn(null);
    when(workflowDagService.isNodeReadyForDispatch(anyLong(), anyLong(), anyString(), any()))
        .thenReturn(true);
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(eq(50L), eq("n1")))
        .thenReturn(null);

    assertThat(
            service.dispatchNode(
                instance(), workflowRun(), new DagNodeResolution("n1", "TASK"), null, "trace"))
        .isZero();
    verify(childJobLaunchSupport, never())
        .dispatchJobNode(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("workflow_node 存在但 cross-day 依赖 halt → 返 0,不进入 dispatch 路径")
  void crossDayDependencyHaltReturnsZero() {
    when(workflowNodeRunMapper.selectLatestForUpdate(anyLong(), anyString())).thenReturn(null);
    when(workflowDagService.isNodeReadyForDispatch(anyLong(), anyLong(), anyString(), any()))
        .thenReturn(true);
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeCode("n1");
    node.setNodeType("TASK");
    node.setCrossDayDependencies(
        "[{\"jobCode\":\"upstream\",\"daysOffset\":-1,\"required\":true}]");
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(eq(50L), eq("n1")))
        .thenReturn(node);
    // 跨日依赖未就绪 → WAITING
    when(crossDayDependencyResolver.resolve(any(), any(), any()))
        .thenReturn(
            CrossDayDependencyResolver.ResolutionResult.builder()
                .status(CrossDayDependencyResolver.ResolutionStatus.WAITING)
                .resolved(java.util.Map.of())
                .waitingReasons(java.util.List.of("upstream"))
                .build());

    assertThat(
            service.dispatchNode(
                instance(), workflowRun(), new DagNodeResolution("n1", "TASK"), null, "trace"))
        .isZero();
    verify(childJobLaunchSupport, never())
        .dispatchJobNode(any(), any(), any(), any(), any(), any());
  }
}
