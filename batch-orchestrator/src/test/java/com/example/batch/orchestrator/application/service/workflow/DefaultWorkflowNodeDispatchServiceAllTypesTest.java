package com.example.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.WorkflowNodeType;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * P2: DefaultWorkflowNodeDispatchService 跨 NodeType 分派路由守护。
 *
 * <p>原 DefaultWorkflowNodeDispatchServiceTest 只覆盖早退分支(null / 已激活)的 10 case;主分支 GATEWAY / JOB / TASK
 * / FILE_STEP / START 的路由方向无单元守护(代码注释"留集成测覆盖")。本测试用 mock 验证按 nodeType 正确路由到对应的下游 collaborator,
 * 防止有人改 isGatewayNode/isJobNode 判断后 silently 把所有节点都走错分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultWorkflowNodeDispatchServiceAllTypesTest {

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
    // 通用 stub:让流程能走到 nodeType 分派点
    when(workflowNodeRunMapper.selectLatestForUpdate(anyLong(), anyString())).thenReturn(null);
    when(workflowDagService.isNodeReadyForDispatch(anyLong(), anyLong(), anyString(), any()))
        .thenReturn(true);
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

  private WorkflowNodeEntity nodeWithType(String type) {
    WorkflowNodeEntity n = new WorkflowNodeEntity();
    n.setId(200L);
    n.setNodeCode("n1");
    n.setNodeType(type);
    n.setRelatedJobCode("JOB_X");
    n.setRelatedPipelineCode("PIPELINE_X");
    n.setTenantId("ta");
    return n;
  }

  @Test
  @DisplayName("JOB 类型 → 走 childJobLaunchSupport.dispatchJobNode 分支")
  void jobNodeRoutesToChildJobLaunchSupport() {
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(eq(50L), eq("n1")))
        .thenReturn(nodeWithType(WorkflowNodeType.JOB.code()));
    when(childJobLaunchSupport.dispatchJobNode(any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    int result =
        service.dispatchNode(
            instance(), workflowRun(), new DagNodeResolution("n1", "JOB"), null, "trace");

    assertThat(result).isEqualTo(1);
    verify(childJobLaunchSupport, times(1))
        .dispatchJobNode(any(), any(), any(), any(), any(), any());
    // JOB 路径不应触碰普通 task 派发器
    verify(schedulePlanBuilder, never()).build(any());
  }

  @Test
  @DisplayName(
      "GATEWAY 节点 → 走 dispatchGatewayNode (无工作负载),不调 childJobLaunchSupport / schedulePlanBuilder")
  void gatewayNodeShouldNotInvokeJobOrTaskDispatch() {
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(eq(50L), eq("n1")))
        .thenReturn(nodeWithType(WorkflowNodeType.GATEWAY.code()));

    // gateway 内部会递归 dispatchNode 下游;这里我们只需断言 JOB / TASK 都没被走
    service.dispatchNode(
        instance(), workflowRun(), new DagNodeResolution("n1", "GATEWAY"), null, "trace");

    verify(childJobLaunchSupport, never())
        .dispatchJobNode(any(), any(), any(), any(), any(), any());
    verify(schedulePlanBuilder, never()).build(any());
  }

  @Test
  @DisplayName("START 节点 → 走 gateway 同分支(isGatewayNode 接受 START)")
  void startNodeShouldBeTreatedAsGateway() {
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(eq(50L), eq("n1")))
        .thenReturn(nodeWithType(WorkflowNodeType.START.code()));

    service.dispatchNode(
        instance(), workflowRun(), new DagNodeResolution("n1", "START"), null, "trace");

    verify(childJobLaunchSupport, never())
        .dispatchJobNode(any(), any(), any(), any(), any(), any());
    verify(schedulePlanBuilder, never()).build(any());
  }
}
