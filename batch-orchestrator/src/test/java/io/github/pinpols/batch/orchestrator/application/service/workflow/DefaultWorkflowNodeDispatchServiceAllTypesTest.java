package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceScheduler;
import io.github.pinpols.batch.orchestrator.application.service.task.ChildJobLaunchSupport;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionLifecycleService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskCreationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService.DagNodeResolution;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * P2: DefaultWorkflowNodeDispatchService 跨 NodeType 分派路由守护。
 *
 * <p>原 DefaultWorkflowNodeDispatchServiceTest 只覆盖早退分支(null / 已激活)的 10 case;主分支 GATEWAY / JOB / TASK
 * / FILE_STEP / START 的路由方向无单元守护(代码注释"留集成测覆盖")。本测试用 mock 验证按 nodeType 正确路由到对应的下游 collaborator,
 * 防止有人改 isGatewayNode/isJobNode 判断后 silently 把所有节点都走错分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("流程节点派发类型分支: 子作业, 网关与起点节点的路由口径")
class DefaultWorkflowNodeDispatchServiceAllTypesTest {

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  @Mock
  private JobPartitionMapper jobPartitionMapper;

  @Mock
  private JobTaskMapper jobTaskMapper;

  @Mock
  private JobStepInstanceMapper jobStepInstanceMapper;

  @Mock
  private TriggerRequestMapper triggerRequestMapper;

  @Mock
  private WorkflowNodeMapper workflowNodeMapper;

  @Mock
  private WorkflowRunMapper workflowRunMapper;

  @Mock
  private WorkflowNodeRunMapper workflowNodeRunMapper;

  @Mock
  private SchedulePlanBuilder schedulePlanBuilder;

  @Mock
  private PartitionLifecycleService partitionLifecycleService;

  @Mock
  private TaskDispatchOutboxService taskDispatchOutboxService;

  @Mock
  private WorkflowDagService workflowDagService;

  @Mock
  private ResourceScheduler resourceScheduler;

  @Mock
  private TaskCreationService taskCreationService;

  @Mock
  private WorkflowNodePayloadBuilder payloadBuilder;

  @Mock
  private ChildJobLaunchSupport childJobLaunchSupport;

  @Mock
  private CrossDayDependencyResolver crossDayDependencyResolver;

  @Mock
  private WorkflowNodeTerminalFinalizer workflowNodeTerminalFinalizer;

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
        crossDayDependencyResolver,
        workflowNodeTerminalFinalizer);
    // 通用 stub:让流程能走到 nodeType 分派点
    when(workflowNodeRunMapper.selectLatestForUpdate(anyLong(), anyString())).thenReturn(null);
    doAnswer(invocation -> {
          ((WorkflowNodeRunEntity) invocation.getArgument(0)).setId(900L);
          return 1;
        })
        .when(workflowNodeRunMapper)
        .insert(any());
    when(workflowNodeRunMapper.attachJobInstance(anyString(), anyLong(), anyLong()))
        .thenReturn(1);
    when(workflowDagService.isNodeReadyForDispatch(anyLong(), anyLong(), anyString(), any()))
        .thenReturn(true);
  }

  private JobInstanceEntity instance() {
    JobInstanceEntity j = new JobInstanceEntity();
    j.setId(100L);
    j.setTenantId("ta");
    j.setBizDate(LocalDate.of(2026, 6, 29));
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
  void shouldRouteToChildJobLaunch_whenNodeIsJob() {
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(50L, "n1"))
        .thenReturn(nodeWithType(WorkflowNodeType.JOB.code()));
    when(childJobLaunchSupport.dispatchJobNode(any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    int result = service.dispatchNode(
        instance(), workflowRun(), new DagNodeResolution("n1", "JOB"), null, "trace");

    assertThat(result).isEqualTo(1);
    verify(childJobLaunchSupport, times(1))
        .dispatchJobNode(any(), any(), any(), any(), any(), any());
    // JOB 路径不应触碰普通 task 派发器
    verify(schedulePlanBuilder, never()).build(any());
  }

  @Test
  @DisplayName("无根 Workflow 的 JOB 节点以 Workflow Run 为上下文，不要求合成父 Job Instance")
  void shouldRouteRootlessJobNodeWithoutParentInstance() {
    WorkflowRunEntity rootlessRun = workflowRun();
    rootlessRun.setRelatedJobInstanceId(null);
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(50L, "n1"))
        .thenReturn(nodeWithType(WorkflowNodeType.JOB.code()));
    when(childJobLaunchSupport.dispatchJobNode(any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    // A report caller may pass the completed node's Job Instance. Rootless Workflow ownership
    // must still prevent it from becoming a synthetic parent for the next node.
    int result = service.dispatchNode(
        instance(), rootlessRun, new DagNodeResolution("n1", "JOB"), null, "trace");

    assertThat(result).isEqualTo(1);
    verify(childJobLaunchSupport)
        .dispatchJobNode(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(rootlessRun),
            any(),
            any(),
            any(),
            anyString());
    verify(schedulePlanBuilder, never()).build(any());
  }

  @Test
  @DisplayName("受管单作业 Workflow 的根 JOB 节点直接派发根实例，不递归启动子作业")
  void shouldDispatchManagedSingleJobRootInline() {
    JobInstanceEntity root = instance();
    root.setId(41L);
    root.setJobCode("JOB_X");
    root.setJobDefinitionId(41L);
    WorkflowRunEntity run = workflowRun();
    run.setSingleJobDefinitionId(41L);
    run.setRelatedJobInstanceId(41L);
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(50L, "n1"))
        .thenReturn(nodeWithType(WorkflowNodeType.JOB.code()));

    int result = service.dispatchNode(root, run, new DagNodeResolution("n1", "JOB"), null, "trace");

    assertThat(result).isZero();
    verify(schedulePlanBuilder).build(any());
    verify(workflowNodeRunMapper).attachJobInstance("ta", 900L, 41L);
    verify(childJobLaunchSupport, never())
        .dispatchJobNode(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "GATEWAY 节点 → 走 dispatchGatewayNode (无工作负载),不调 childJobLaunchSupport / schedulePlanBuilder")
  void shouldRouteToGateway_whenNodeIsGateway() {
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(50L, "n1"))
        .thenReturn(nodeWithType(WorkflowNodeType.GATEWAY.code()));

    // gateway 内部会递归 dispatchNode 下游;这里我们只需断言 JOB / TASK 都没被走
    service.dispatchNode(
        instance(), workflowRun(), new DagNodeResolution("n1", "GATEWAY"), null, "trace");

    verify(childJobLaunchSupport, never())
        .dispatchJobNode(any(), any(), any(), any(), any(), any());
    verify(schedulePlanBuilder, never()).build(any());
    verify(workflowNodeTerminalFinalizer).finalizeIfAllNodesTerminal(any());
  }

  @Test
  @DisplayName("START 节点 → 走 gateway 同分支(isGatewayNode 接受 START)")
  void shouldTreatAsGateway_whenNodeIsStart() {
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(50L, "n1"))
        .thenReturn(nodeWithType(WorkflowNodeType.START.code()));

    service.dispatchNode(
        instance(), workflowRun(), new DagNodeResolution("n1", "START"), null, "trace");

    verify(childJobLaunchSupport, never())
        .dispatchJobNode(any(), any(), any(), any(), any(), any());
    verify(schedulePlanBuilder, never()).build(any());
    verify(workflowNodeTerminalFinalizer).finalizeIfAllNodesTerminal(any());
  }
}
