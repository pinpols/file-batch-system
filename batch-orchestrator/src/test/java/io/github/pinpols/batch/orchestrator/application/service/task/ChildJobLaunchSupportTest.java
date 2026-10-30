package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodePayloadBuilder;
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
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow 节点 Job 启动")
class ChildJobLaunchSupportTest {

  private static final String TENANT = "t1";

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
  private SecretPayloadProtector secretPayloadProtector;

  @Mock
  private ObjectProvider<LaunchService> launchServiceProvider;

  @Mock
  private WorkflowNodePayloadBuilder payloadBuilder;

  private ChildJobLaunchSupport newSupport() {
    return new ChildJobLaunchSupport(
        new OrchestratorJobMappers(
            jobInstanceMapper,
            jobPartitionMapper,
            jobTaskMapper,
            jobStepInstanceMapper,
            triggerRequestMapper),
        new OrchestratorWorkflowMappers(
            workflowNodeMapper, workflowRunMapper, workflowNodeRunMapper),
        launchServiceProvider,
        payloadBuilder,
        secretPayloadProtector);
  }

  private WorkflowRunEntity run() {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(900L);
    run.setTenantId(TENANT);
    run.setBizDate(LocalDate.of(2026, 10, 14));
    run.setDryRun(true);
    return run;
  }

  private WorkflowRunEntity replayRun() {
    WorkflowRunEntity run = run();
    run.setWorkflowDefinitionId(80L);
    run.setWorkflowVersionSnapshot(3);
    Map<String, Object> replayDeclaration = Map.of(
        "pluginId",
        "jdbc_mapped",
        "pluginVersion",
        "1",
        "externalEffectCategories",
        List.of("BUSINESS_DATABASE"),
        "idempotencyCapability",
        "IDEMPOTENT_BY_PLUGIN_LOGIC",
        "idempotencyKeyScope",
        "tenant+business-key",
        "recoveryProtocolId",
        "IMPORT_SQL_V1",
        "contractVersion",
        1);
    Map<String, Object> replayGuard =
        Map.of("replayOperationId", 701L, "declaration", replayDeclaration);
    Map<String, Object> executionDefinition =
        Map.of("id", 1, "tenantId", TENANT, "jobCode", "JOB_B", "jobType", "IMPORT", "version", 1);
    Map<String, Object> jobDefinitionSnapshot = Map.of(
        "jobDefinitionId",
        1,
        "jobCode",
        "JOB_B",
        "jobType",
        "IMPORT",
        "jobDefinitionVersion",
        1,
        "systemPipelineCode",
        "IMPORT",
        "systemPipelineVersion",
        1,
        "executionDefinition",
        executionDefinition,
        "replayExecutionGuard",
        replayGuard,
        "frozenFileTemplateSnapshots",
        Map.of("TPL_A", "sealed-template-a"));
    Map<String, Object> nodeSnapshot = Map.of(
        "nodeCode", "N1",
        "nodeType", "JOB",
        "tenantId", TENANT,
        "workflowDefinitionId", 80,
        "relatedJobCode", "JOB_B");
    Map<String, Object> executionSnapshot = Map.of(
        "schemaVersion",
        2,
        "workflowDefinitionId",
        80,
        "workflowVersion",
        3,
        "workflowCalendarCode",
        "CALENDAR_A",
        "replayOperationId",
        701L,
        "nodes",
        List.of(nodeSnapshot),
        "edges",
        List.of(),
        "jobDefinitions",
        Map.of("N1", jobDefinitionSnapshot));
    String serializedExecutionSnapshot = JsonUtils.toJson(executionSnapshot);
    run.setExecutionSnapshot(serializedExecutionSnapshot);
    return run;
  }

  private WorkflowDagService.DagNodeResolution resolution() {
    return new WorkflowDagService.DagNodeResolution("N1", "JOB");
  }

  private WorkflowNodeEntity jobNode() {
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeCode("N1");
    node.setNodeType("JOB");
    node.setRelatedJobCode("JOB_B");
    return node;
  }

  @Test
  @DisplayName("节点启动子 Job 并只建立节点到 Job 实例的归属关系")
  void shouldLaunchesChildJobOwnedByWorkflowNode() {
    when(triggerRequestMapper.insert(any(TriggerRequestEntity.class))).thenReturn(1);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(900L, "N1"))
        .thenReturn(null);
    when(workflowNodeRunMapper.insertIfAbsent(any(WorkflowNodeRunEntity.class)))
        .thenAnswer(invocation -> {
          WorkflowNodeRunEntity nodeRun = invocation.getArgument(0);
          nodeRun.setId(901L);
          return 1;
        });
    LaunchService launchService = mock(LaunchService.class);
    when(launchServiceProvider.getObject()).thenReturn(launchService);
    when(launchService.launch(any(LaunchRequest.class)))
        .thenReturn(new LaunchResponse("child-1", "trace-1"));
    JobInstanceEntity child = new JobInstanceEntity();
    child.setId(7L);
    child.setTenantId(TENANT);
    when(jobInstanceMapper.selectByInstanceNo(TENANT, "child-1")).thenReturn(child);
    when(workflowNodeRunMapper.attachJobInstance(TENANT, 901L, 7L)).thenReturn(1);
    when(secretPayloadProtector.protect("{\"source\":\"test\"}"))
        .thenReturn("protected-source-payload");

    int dispatched = newSupport()
        .dispatchJobNode(run(), resolution(), jobNode(), "{\"source\":\"test\"}", "trace-1");

    assertThat(dispatched).isEqualTo(1);
    ArgumentCaptor<WorkflowNodeRunEntity> nodeRunCaptor =
        ArgumentCaptor.forClass(WorkflowNodeRunEntity.class);
    verify(workflowNodeRunMapper).insertIfAbsent(nodeRunCaptor.capture());
    assertThat(nodeRunCaptor.getValue().getSourcePayloadProtected())
        .isEqualTo("protected-source-payload");
    ArgumentCaptor<TriggerRequestEntity> triggerCaptor =
        ArgumentCaptor.forClass(TriggerRequestEntity.class);
    verify(triggerRequestMapper).insert(triggerCaptor.capture());
    assertThat(triggerCaptor.getValue().getParentWorkflowRunId()).isEqualTo(900L);
    assertThat(triggerCaptor.getValue().getParentNodeCode()).isEqualTo("N1");
    assertThat(triggerCaptor.getValue().getDryRun()).isTrue();
    ArgumentCaptor<LaunchRequest> launchCaptor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchService).launch(launchCaptor.capture());
    Map<String, Object> params = launchCaptor.getValue().params();
    assertThat(params)
        .containsEntry("_parentWorkflowRunId", 900L)
        .containsEntry("_parentNodeCode", "N1")
        .doesNotContainKey("_parentVirtualTaskId")
        .doesNotContainKey("parentInstanceId");
    assertThat(launchCaptor.getValue().dryRun()).isTrue();
    verify(workflowNodeRunMapper).attachJobInstance(TENANT, 901L, 7L);
    verify(jobPartitionMapper, never()).insert(any());
    verify(jobTaskMapper, never()).insert(any());
  }

  @Test
  @DisplayName("整 Workflow Replay 子 Job 显式携带执行类型以禁用模板 latest 回退")
  void shouldMarkChildLaunchAsWorkflowReplay() {
    when(triggerRequestMapper.insert(any(TriggerRequestEntity.class))).thenReturn(1);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(900L, "N1"))
        .thenReturn(null);
    when(workflowNodeRunMapper.insertIfAbsent(any(WorkflowNodeRunEntity.class)))
        .thenAnswer(invocation -> {
          ((WorkflowNodeRunEntity) invocation.getArgument(0)).setId(901L);
          return 1;
        });
    LaunchService launchService = mock(LaunchService.class);
    when(launchServiceProvider.getObject()).thenReturn(launchService);
    when(launchService.launch(any(LaunchRequest.class)))
        .thenReturn(new LaunchResponse("child-replay", "trace-replay"));
    JobInstanceEntity child = new JobInstanceEntity();
    child.setId(9L);
    child.setTenantId(TENANT);
    when(jobInstanceMapper.selectByInstanceNo(TENANT, "child-replay")).thenReturn(child);
    when(workflowNodeRunMapper.attachJobInstance(TENANT, 901L, 9L)).thenReturn(1);

    newSupport()
        .dispatchJobNode(
            replayRun(), resolution(), jobNode(), "{\"templateCode\":\"TPL_A\"}", "trace-replay");

    ArgumentCaptor<LaunchRequest> launchCaptor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchService).launch(launchCaptor.capture());
    assertThat(launchCaptor.getValue().params())
        .containsEntry("operationType", "WORKFLOW_RUN")
        .containsEntry("templateCode", "TPL_A")
        .containsEntry("systemPipelineCode", "IMPORT");
  }

  @Test
  @DisplayName("跨日结果版本随节点参数冻结传入子 Job")
  void shouldPreservesPinnedCrossDayResultInChildJobParameters() {
    when(triggerRequestMapper.insert(any(TriggerRequestEntity.class))).thenReturn(1);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(900L, "N1"))
        .thenReturn(null);
    when(workflowNodeRunMapper.insertIfAbsent(any(WorkflowNodeRunEntity.class)))
        .thenAnswer(invocation -> {
          WorkflowNodeRunEntity nodeRun = invocation.getArgument(0);
          nodeRun.setId(901L);
          return 1;
        });
    LaunchService launchService = mock(LaunchService.class);
    when(launchServiceProvider.getObject()).thenReturn(launchService);
    when(launchService.launch(any(LaunchRequest.class)))
        .thenReturn(new LaunchResponse("child-pinned", "trace-pinned"));
    JobInstanceEntity child = new JobInstanceEntity();
    child.setId(8L);
    child.setTenantId(TENANT);
    when(jobInstanceMapper.selectByInstanceNo(TENANT, "child-pinned")).thenReturn(child);
    when(workflowNodeRunMapper.attachJobInstance(TENANT, 901L, 8L)).thenReturn(1);

    String sourcePayload = """
        {"crossDay":{"prior_day":{"resultVersionId":9876,"versionNo":3,
        "businessKey":"job:JOB_A:2026-10-13","payloadStorage":"INLINE_JSON",
        "output":{"total":42}}}}
        """;
    newSupport().dispatchJobNode(run(), resolution(), jobNode(), sourcePayload, "trace-pinned");

    ArgumentCaptor<LaunchRequest> launchCaptor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(launchService).launch(launchCaptor.capture());
    Map<?, ?> crossDay = (Map<?, ?>) launchCaptor.getValue().params().get("crossDay");
    Map<?, ?> pinned = (Map<?, ?>) crossDay.get("prior_day");
    assertThat(pinned.get("resultVersionId")).isEqualTo(9876);
    assertThat(pinned.get("versionNo")).isEqualTo(3);
    assertThat(pinned.get("businessKey")).isEqualTo("job:JOB_A:2026-10-13");
    assertThat(pinned.get("payloadStorage")).isEqualTo("INLINE_JSON");
    assertThat(pinned.get("output")).isEqualTo(Map.of("total", 42));
  }

  @Test
  @DisplayName("原请求插入零行时拒绝启动，不向无持久身份的子Job派发")
  void shouldRejectChildWhenOriginalRequestInsertReturnsZero() {
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(900L, "N1"))
        .thenReturn(null);
    when(workflowNodeRunMapper.insertIfAbsent(any(WorkflowNodeRunEntity.class)))
        .thenAnswer(invocation -> {
          ((WorkflowNodeRunEntity) invocation.getArgument(0)).setId(901L);
          return 1;
        });
    ChildJobLaunchSupport support = newSupport();
    when(triggerRequestMapper.insert(any(TriggerRequestEntity.class))).thenReturn(0);

    assertThatThrownBy(
            () -> support.dispatchJobNode(run(), resolution(), jobNode(), "{}", "trace-zero"))
        .isInstanceOf(BizException.class)
        .extracting("messageKey")
        .isEqualTo("error.workflow.child_job_launch_rejected");
    verify(launchServiceProvider, never()).getObject();
  }

  @Test
  @DisplayName("批量日闸门等待时保留 READY 节点，不要求同步创建 Job Instance")
  void shouldKeepsNodeReadyWhenBatchDayGateDefersChildLaunch() {
    when(triggerRequestMapper.insert(any(TriggerRequestEntity.class))).thenReturn(1);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(900L, "N1"))
        .thenReturn(null);
    when(workflowNodeRunMapper.insertIfAbsent(any(WorkflowNodeRunEntity.class)))
        .thenAnswer(invocation -> {
          ((WorkflowNodeRunEntity) invocation.getArgument(0)).setId(901L);
          return 1;
        });
    LaunchService launchService = mock(LaunchService.class);
    when(launchServiceProvider.getObject()).thenReturn(launchService);
    when(launchService.launch(any(LaunchRequest.class)))
        .thenReturn(LaunchResponse.skipped("trace-wait"));
    TriggerRequestEntity waiting = new TriggerRequestEntity();
    waiting.setRequestStatus(TriggerRequestStatus.WAITING.code());
    when(triggerRequestMapper.selectByTenantAndRequestId(anyString(), anyString()))
        .thenReturn(waiting);

    int dispatched =
        newSupport().dispatchJobNode(run(), resolution(), jobNode(), "{}", "trace-wait");

    assertThat(dispatched).isZero();
    verify(workflowNodeRunMapper, never()).attachJobInstance(anyString(), any(), any());
    verify(jobPartitionMapper, never()).insert(any());
    verify(jobTaskMapper, never()).insert(any());
  }
}
