package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceScheduler;
import io.github.pinpols.batch.orchestrator.application.service.task.ChildJobLaunchSupport;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionLifecycleService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskCreationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService.DagNodeResolution;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow 节点派发的根归属和重复激活约束")
class DefaultWorkflowNodeDispatchServiceTest {

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
  private BatchDayInstanceMapper batchDayInstanceMapper;

  @Mock
  private WorkflowDefinitionMapper workflowDefinitionMapper;

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

  @Mock
  private SecretPayloadProtector secretPayloadProtector;

  private DefaultWorkflowNodeDispatchService service;

  @BeforeEach
  void setUp() {
    service = new DefaultWorkflowNodeDispatchService(
        new OrchestratorJobMappers(
            jobInstanceMapper,
            jobPartitionMapper,
            jobTaskMapper,
            jobStepInstanceMapper,
            triggerRequestMapper),
        new OrchestratorWorkflowMappers(
            workflowNodeMapper, workflowRunMapper, workflowNodeRunMapper),
        schedulePlanBuilder,
        partitionLifecycleService,
        taskDispatchOutboxService,
        workflowDagService,
        resourceScheduler,
        taskCreationService,
        payloadBuilder,
        childJobLaunchSupport,
        crossDayDependencyResolver,
        workflowNodeTerminalFinalizer,
        secretPayloadProtector,
        Mockito.mock(JobDependencyWaitingLaunchMapper.class),
        batchDayInstanceMapper,
        workflowDefinitionMapper);
    lenient()
        .when(workflowDefinitionMapper.selectById(50L))
        .thenReturn(new WorkflowDefinitionEntity(
            50L, "ta", "FLOW", "Flow", "COMPOSITE", 1, true, null, "CAL"));
    lenient()
        .when(workflowRunMapper.selectByTenantAndIdForUpdate("ta", 10L))
        .thenAnswer(invocation -> workflowRun());
  }

  @Test
  @DisplayName("缺少 Workflow Run 或节点时不访问存储")
  void shouldReturnZeroWhenRequiredInputMissing() {
    assertThat(service.dispatchNode(null, workflowRun(), resolution("JOB"), null, "trace"))
        .isZero();
    assertThat(service.dispatchNode(null, null, resolution("JOB"), null, "trace"))
        .isZero();
    assertThat(service.dispatchNode(null, workflowRun(), null, null, "trace")).isZero();
    verify(workflowNodeRunMapper, times(1)).selectLatestForUpdate(any(), anyString());
  }

  @Test
  @DisplayName("拒绝旧 Job Instance 根运行，避免恢复 Job 根复用语义")
  void shouldRejectLegacyJobInstanceRoot() {
    WorkflowRunEntity run = workflowRun();
    run.setRelatedJobInstanceId(100L);

    assertThatThrownBy(
            () -> service.dispatchNode(jobInstance(), run, resolution("JOB"), null, "trace"))
        .isInstanceOf(BizException.class)
        .hasMessage("error.workflow.root_job_launch_retired");
    verify(workflowNodeRunMapper, never()).selectLatestForUpdate(any(), anyString());
  }

  @Test
  @DisplayName("暂停中的 Workflow 不激活新节点")
  void shouldNotDispatchPausedWorkflow() {
    WorkflowRunEntity run = workflowRun();
    run.setRunStatus("PAUSED");
    when(workflowRunMapper.selectByTenantAndIdForUpdate("ta", 10L)).thenReturn(run);

    assertThat(service.dispatchNode(jobInstance(), run, resolution("JOB"), null, "trace"))
        .isZero();
    verify(workflowNodeRunMapper, never()).selectLatestForUpdate(any(), anyString());
  }

  @Test
  @DisplayName("已激活节点不会再次检查 readiness 或分发")
  void shouldSkipAlreadyActivatedNode() {
    WorkflowNodeRunEntity existing = new WorkflowNodeRunEntity();
    existing.setNodeStatus(WorkflowNodeRunStatus.READY.code());
    when(workflowNodeRunMapper.selectLatestForUpdate(10L, "N1")).thenReturn(existing);

    assertThat(service.dispatchNode(jobInstance(), workflowRun(), resolution("JOB"), null, "trace"))
        .isZero();
    verify(workflowDagService, never()).isNodeReadyForRunDispatch(any(), anyString(), any());
    verify(childJobLaunchSupport, never()).dispatchJobNode(any(), any(), any(), any(), anyString());
  }

  @ParameterizedTest(name = "父运行终态 {0} 时不派发后续节点")
  @MethodSource("terminalRunStatuses")
  void shouldSkipDispatchWhenParentWorkflowIsTerminal(String terminalStatus) {
    WorkflowRunEntity terminated = workflowRun();
    terminated.setRunStatus(terminalStatus);
    when(workflowRunMapper.selectByTenantAndIdForUpdate("ta", 10L)).thenReturn(terminated);

    assertThat(service.dispatchNode(jobInstance(), workflowRun(), resolution("JOB"), null, "trace"))
        .isZero();
    verify(batchDayInstanceMapper).acquireSettlementFence("ta", "CAL", LocalDate.of(2026, 6, 29));
    verify(workflowNodeRunMapper, never()).selectLatestForUpdate(any(), anyString());
  }

  @Test
  @DisplayName("冻结 Workflow 快照决定批次日围栏且不重读当前定义日历")
  void shouldUseFrozenCalendarForSettlementFence_whenWorkflowDefinitionCalendarChanges() {
    WorkflowRunEntity run = workflowRun();
    run.setWorkflowVersionSnapshot(1);
    run.setExecutionSnapshot(frozenCalendarSnapshot());
    WorkflowNodeRunEntity activated = new WorkflowNodeRunEntity();
    activated.setNodeStatus(WorkflowNodeRunStatus.READY.code());
    when(workflowNodeRunMapper.selectLatestForUpdate(10L, "N1")).thenReturn(activated);
    when(workflowRunMapper.selectByTenantAndIdForUpdate("ta", 10L)).thenReturn(run);

    assertThat(service.dispatchNode(jobInstance(), run, resolution("JOB"), "{}", "trace"))
        .isZero();

    verify(batchDayInstanceMapper)
        .acquireSettlementFence("ta", "FROZEN_CALENDAR", LocalDate.of(2026, 6, 29));
    verify(workflowDefinitionMapper, never()).selectById(50L);
  }

  private static Stream<String> terminalRunStatuses() {
    return WorkflowRunStatus.terminalCodes().stream();
  }

  @Test
  @DisplayName("DAG readiness 未满足时不读取节点定义")
  void shouldSkipNodeWhenDagIsNotReady() {
    when(workflowNodeRunMapper.selectLatestForUpdate(10L, "N1")).thenReturn(null);
    when(workflowDagService.isNodeReadyForRunDispatch(any(), eq("N1"), any())).thenReturn(false);

    assertThat(service.dispatchNode(jobInstance(), workflowRun(), resolution("JOB"), null, "trace"))
        .isZero();
    verify(workflowNodeMapper, never()).selectByWorkflowDefinitionIdAndNodeCode(any(), anyString());
  }

  @Test
  @DisplayName("快照外节点定义缺失时不创建节点运行或子 Job")
  void shouldSkipWhenNodeDefinitionMissing() {
    when(workflowNodeRunMapper.selectLatestForUpdate(10L, "N1")).thenReturn(null);
    when(workflowDagService.isNodeReadyForRunDispatch(any(), eq("N1"), any())).thenReturn(true);
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(50L, "N1")).thenReturn(null);

    assertThat(service.dispatchNode(jobInstance(), workflowRun(), resolution("JOB"), null, "trace"))
        .isZero();
    verify(childJobLaunchSupport, never()).dispatchJobNode(any(), any(), any(), any(), anyString());
  }

  @Test
  @DisplayName("JOB 节点通过 Workflow Run 启动子 Job，不把报告携带的实例当作父容器")
  void shouldDispatchJobNodeFromWorkflowRoot() {
    when(workflowNodeRunMapper.selectLatestForUpdate(10L, "N1")).thenReturn(null);
    when(workflowDagService.isNodeReadyForRunDispatch(any(), eq("N1"), any())).thenReturn(true);
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeCode("N1");
    node.setNodeType("JOB");
    node.setRelatedJobCode("JOB_B");
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(50L, "N1")).thenReturn(node);
    when(childJobLaunchSupport.dispatchJobNode(
            any(WorkflowRunEntity.class), any(), any(), any(), anyString()))
        .thenReturn(1);

    assertThat(service.dispatchNode(jobInstance(), workflowRun(), resolution("JOB"), "{}", "trace"))
        .isEqualTo(1);
    verify(childJobLaunchSupport)
        .dispatchJobNode(any(WorkflowRunEntity.class), any(), eq(node), eq("{}"), eq("trace"));
  }

  @Test
  @DisplayName("整 Workflow 重放派发只按快照中的结果版本解析跨日依赖")
  void shouldResolvePinnedCrossDayVersions_whenWorkflowReplayIsDispatched() {
    WorkflowRunEntity run = workflowRun();
    run.setWorkflowVersionSnapshot(1);
    run.setExecutionSnapshot(workflowReplaySnapshot());
    when(workflowRunMapper.selectByTenantAndIdForUpdate("ta", 10L)).thenReturn(run);
    when(workflowNodeRunMapper.selectLatestForUpdate(10L, "N1")).thenReturn(null);
    when(workflowDagService.isNodeReadyForRunDispatch(any(), eq("N1"), any())).thenReturn(true);
    when(crossDayDependencyResolver.resolvePinned(
            eq("ta"),
            eq(LocalDate.of(2026, 6, 29)),
            eq("[{\"alias\":\"previous\",\"jobCode\":\"JOB_PREVIOUS\",\"bizDateOffset\":-1}]"),
            eq(false),
            eq(Map.of("previous", List.of(812L)))))
        .thenReturn(CrossDayDependencyResolver.ResolutionResult.builder()
            .status(CrossDayDependencyResolver.ResolutionStatus.RESOLVED)
            .resolved(Map.of("previous", Map.of("output", Map.of("value", "frozen"))))
            .waitingReasons(List.of())
            .resultVersionIdsByAlias(Map.of("previous", List.of(812L)))
            .build());
    when(childJobLaunchSupport.dispatchJobNode(
            any(WorkflowRunEntity.class), any(), any(), anyString(), anyString()))
        .thenReturn(1);

    assertThat(service.dispatchNode(jobInstance(), run, resolution("JOB"), "{}", "trace"))
        .isEqualTo(1);

    verify(crossDayDependencyResolver).resolvePinned(
        "ta",
        LocalDate.of(2026, 6, 29),
        "[{\"alias\":\"previous\",\"jobCode\":\"JOB_PREVIOUS\",\"bizDateOffset\":-1}]",
        false,
        Map.of("previous", List.of(812L)));
    verify(crossDayDependencyResolver, never())
        .resolve(anyString(), any(), anyString(), eq(false));
  }

  @Test
  @DisplayName("遗留 TASK 节点没有 Job 根时明确拒绝，不尝试合成父实例")
  void shouldRejectLegacyTaskNodeWithoutJobRoot() {
    when(workflowNodeRunMapper.selectLatestForUpdate(10L, "N1")).thenReturn(null);
    when(workflowDagService.isNodeReadyForRunDispatch(any(), eq("N1"), any())).thenReturn(true);
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeCode("N1");
    node.setNodeType("TASK");
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(50L, "N1")).thenReturn(node);

    assertThatThrownBy(() ->
            service.dispatchNode(jobInstance(), workflowRun(), resolution("TASK"), "{}", "trace"))
        .isInstanceOf(BizException.class)
        .hasMessage("error.workflow.legacy_task_node_requires_job_migration");
    verify(schedulePlanBuilder, never()).build(any());
  }

  private WorkflowRunEntity workflowRun() {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(10L);
    run.setWorkflowDefinitionId(50L);
    run.setTenantId("ta");
    run.setBizDate(LocalDate.of(2026, 6, 29));
    run.setRunStatus("RUNNING");
    return run;
  }

  private String frozenCalendarSnapshot() {
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setTenantId("ta");
    node.setWorkflowDefinitionId(50L);
    node.setNodeCode("N1");
    node.setNodeType("JOB");
    node.setRelatedJobCode("JOB_A");
    node.setEnabled(true);
    JobDefinitionEntity job = JobDefinitionEntity.builder()
        .id(101L)
        .tenantId("ta")
        .jobCode("JOB_A")
        .jobType("ATOMIC")
        .version(1)
        .build();
    Map<String, Object> jobIdentity = Map.of(
        "jobDefinitionId", job.id(),
        "jobCode", job.jobCode(),
        "jobType", job.jobType(),
        "jobDefinitionVersion", job.version(),
        "executionDefinition", job);
    Map<String, Object> snapshot = Map.of(
        "schemaVersion", 2,
        "workflowDefinitionId", 50L,
        "workflowVersion", 1,
        "workflowCalendarCode", "FROZEN_CALENDAR",
        "replaySessionId", "",
        "nodes", List.of(node),
        "edges", List.of(),
        "jobDefinitions", Map.of("N1", jobIdentity));
    return JsonUtils.toJson(snapshot);
  }

  private String workflowReplaySnapshot() {
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setTenantId("ta");
    node.setWorkflowDefinitionId(50L);
    node.setNodeCode("N1");
    node.setNodeType("JOB");
    node.setRelatedJobCode("JOB_A");
    node.setEnabled(true);
    node.setCrossDayDependencies(
        "[{\"alias\":\"previous\",\"jobCode\":\"JOB_PREVIOUS\",\"bizDateOffset\":-1}]");
    JobDefinitionEntity definition = JobDefinitionEntity.builder()
        .id(101L)
        .tenantId("ta")
        .jobCode("JOB_A")
        .jobType("ATOMIC")
        .version(1)
        .build();
    Map<String, Object> jobMetadata = Map.of(
        "jobDefinitionId", 101L,
        "jobCode", "JOB_A",
        "jobType", "ATOMIC",
        "jobDefinitionVersion", 1,
        "executionDefinition", definition,
        "replayExecutionGuard", Map.of(
            "replayOperationId", 701L,
            "declaration", Map.of(
                "pluginId", "atomic-plugin",
                "pluginVersion", "1.0.0",
                "externalEffectCategories", List.of("DATABASE_WRITE"),
                "idempotencyCapability", "IDEMPOTENT_BY_UNIQUE_CONSTRAINT",
                "idempotencyKeyScope", "tenant+business-key",
                "recoveryProtocolId", "unique-key",
                "contractVersion", 1)));
    return JsonUtils.toJson(Map.of(
        "schemaVersion", 2,
        "workflowDefinitionId", 50L,
        "workflowVersion", 1,
        "workflowCalendarCode", "CAL",
        "replayOperationId", 701L,
        "nodes", List.of(node),
        "edges", List.of(),
        "jobDefinitions", Map.of("N1", jobMetadata),
        "crossDayDependencyResultVersionIdsByNode", Map.of("N1", Map.of("previous", List.of(812L)))));
  }

  private JobInstanceEntity jobInstance() {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(100L);
    instance.setTenantId("ta");
    instance.setJobCode("JOB_A");
    instance.setBizDate(LocalDate.of(2026, 6, 29));
    return instance;
  }

  private DagNodeResolution resolution(String type) {
    return new DagNodeResolution("N1", type);
  }
}
