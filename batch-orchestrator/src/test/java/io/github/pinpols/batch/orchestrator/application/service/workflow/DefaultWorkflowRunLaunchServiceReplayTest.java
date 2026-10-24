package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.orchestrator.domain.param.WorkflowRunMarkRunningParam;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowRunActiveByTenantWorkflowBizDateQuery;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ReplayOperationStatus;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExternalEventExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RelatedFileReferenceValidator;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplaySnapshotResolver;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchParamResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("整 Workflow Replay 从审批快照启动，不重读可变定义")
class DefaultWorkflowRunLaunchServiceReplayTest {

  private static final String TENANT = "tenant-replay-launch";
  private static final String REQUEST_ID = "workflow-replay-808";
  private static final Long TRIGGER_REQUEST_ID = 909L;
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 20);

  @Mock
  private TriggerRequestMapper triggerRequests;

  @Mock
  private WorkflowDefinitionMapper workflowDefinitions;

  @Mock
  private WorkflowRunMapper workflowRuns;

  @Mock
  private ReplayOperationMapper replayOperations;

  @Mock
  private JobInstanceMapper jobInstances;

  @Mock
  private LaunchParamResolver launchParams;

  @Mock
  private WorkflowNodeRunMapper nodeRuns;

  @Mock
  private WorkflowNodeMapper nodes;

  @Mock
  private WorkflowEdgeMapper edges;

  @Mock
  private WorkflowGraphValidator graphValidator;

  @Mock
  private WorkflowDagService dagService;

  @Mock
  private WorkflowNodeDispatchService nodeDispatch;

  @Mock
  private OrchestratorConfigCacheService configCache;

  @Mock
  private RerunJobDefinitionResolver rerunDefinitions;

  @Mock
  private WorkflowLaunchWaitService waitingLaunch;

  @Mock
  private ExternalEventExpectationService eventExpectations;

  @Mock
  private RelatedFileReferenceValidator fileReferences;

  @Mock
  private SecretPayloadProtector secretPayloadProtector;

  @Mock
  private WorkflowRunReplaySnapshotResolver replaySnapshotResolver;

  @Mock
  private WorkflowNodeTerminalFinalizer terminalFinalizer;

  private DefaultWorkflowRunLaunchService service;

  @BeforeEach
  void setUp() {
    service = new DefaultWorkflowRunLaunchService(
        triggerRequests,
        workflowDefinitions,
        workflowRuns,
        replayOperations,
        replaySnapshotResolver,
        jobInstances,
        launchParams,
        nodeRuns,
        nodes,
        edges,
        graphValidator,
        dagService,
        nodeDispatch,
        configCache,
        rerunDefinitions,
        noOpTransactions(),
        waitingLaunch,
        eventExpectations,
        fileReferences,
        secretPayloadProtector,
        terminalFinalizer);
  }

  @Test
  @DisplayName("创建新 RootRun 时使用审批冻结的图、日历、参数和 Job 配置")
  void shouldLaunchFromApprovedSnapshot_whenCurrentWorkflowDefinitionIsUnavailable() {
    ReplayOperationEntity operation = approvedOperation();
    WorkflowRunReplaySnapshotResolver.Snapshot snapshot = approvedSnapshot();
    TriggerRequestEntity accepted = acceptedTriggerRequest();
    when(triggerRequests.selectByTenantAndRequestId(TENANT, REQUEST_ID)).thenReturn(accepted);
    when(workflowRuns.selectByTenantAndTriggerRequestId(TENANT, TRIGGER_REQUEST_ID))
        .thenReturn(null, null, null);
    when(replayOperations.selectByTargetRequest(TENANT, REQUEST_ID)).thenReturn(operation);
    when(replaySnapshotResolver.resolve(operation, REQUEST_ID)).thenReturn(snapshot);
    when(triggerRequests.selectByTenantAndRequestIdForUpdate(TENANT, REQUEST_ID))
        .thenReturn(accepted);
    when(replayOperations.attachTargetWorkflowRun(
            org.mockito.ArgumentMatchers.eq(TENANT),
            org.mockito.ArgumentMatchers.eq(REQUEST_ID),
            org.mockito.ArgumentMatchers.eq(990L),
            org.mockito.ArgumentMatchers.any(Instant.class)))
        .thenReturn(1);
    when(workflowRuns.markRunning(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.tenantId(), TENANT) && java.util.Objects.equals(parameters.id(), 990L) && java.util.Objects.equals(parameters.runStatus(), "RUNNING") && java.util.Objects.equals(parameters.currentNodeCode(), "FIRST") && parameters.startedAt() != null)))
        .thenReturn(1);
    doAnswer(invocation -> {
      WorkflowRunEntity created = invocation.getArgument(0);
      created.setId(990L);
      return 1;
    }).when(workflowRuns).insertIfActiveSlotFree(org.mockito.ArgumentMatchers.any(WorkflowRunEntity.class));
    when(nodeRuns.insert(org.mockito.ArgumentMatchers.any(WorkflowNodeRunEntity.class)))
        .thenReturn(1);
    when(secretPayloadProtector.protect(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn("sealed-launch-params");

    service.launch(transportRequest());

    ArgumentCaptor<WorkflowRunEntity> rootRunCaptor =
        ArgumentCaptor.forClass(WorkflowRunEntity.class);
    verify(workflowRuns).insertIfActiveSlotFree(rootRunCaptor.capture());
    WorkflowRunEntity created = rootRunCaptor.getValue();
    org.mockito.InOrder completionOrder = org.mockito.Mockito.inOrder(dagService, terminalFinalizer);
    completionOrder.verify(dagService).cascadeSkipDownstreamForRun(
        org.mockito.ArgumentMatchers.eq(created), org.mockito.ArgumentMatchers.eq("START"),
        org.mockito.ArgumentMatchers.anyString());
    completionOrder.verify(terminalFinalizer).finalizeIfAllNodesTerminal(created);
    assertThat(created.getWorkflowDefinitionId()).isEqualTo(81L);
    assertThat(created.getSingleJobDefinitionId()).isEqualTo(700L);
    assertThat(created.getWorkflowVersionSnapshot()).isEqualTo(4);
    assertThat(created.getBizDate()).isEqualTo(BIZ_DATE);
    assertThat(created.getExecutionSnapshot())
        .isEqualTo("{\"initialNodes\":[{\"nodeCode\":\"FIRST\",\"nodeType\":\"JOB\"}]}");
    assertThat(created.getDataIntervalStart()).isEqualTo(snapshot.dataIntervalStart());
    assertThat(created.getDryRun()).isFalse();
    ArgumentCaptor<WorkflowNodeRunEntity> startCaptor =
        ArgumentCaptor.forClass(WorkflowNodeRunEntity.class);
    verify(nodeRuns).insert(startCaptor.capture());
    assertThat(startCaptor.getValue().getSourcePayloadProtected()).isEqualTo("sealed-launch-params");
    verify(workflowDefinitions, never()).selectByTenantAndIdAndEnabled(TENANT, 81L, true);
    verify(configCache, never()).findEnabledJobDefinition(TENANT, "COMPUTE_A");
    verify(replayOperations).attachTargetWorkflowRun(
        org.mockito.ArgumentMatchers.eq(TENANT),
        org.mockito.ArgumentMatchers.eq(REQUEST_ID),
        org.mockito.ArgumentMatchers.eq(990L),
        org.mockito.ArgumentMatchers.any(Instant.class));
    verify(nodeDispatch).dispatchNode(
        org.mockito.ArgumentMatchers.isNull(),
        org.mockito.ArgumentMatchers.any(WorkflowRunEntity.class),
        org.mockito.ArgumentMatchers.argThat(node -> "FIRST".equals(node.nodeCode())),
        org.mockito.ArgumentMatchers.contains("_rerunResultPolicy"),
        org.mockito.ArgumentMatchers.eq("trace-replay-808"));
  }

  @Test
  @DisplayName("运行中的 operation 不匹配 target request 时拒绝启动")
  void shouldRejectWrongTargetRequest_whenReplayOperationIsAlreadyBoundElsewhere() {
    ReplayOperationEntity operation = approvedOperation().toBuilder()
        .targetRequestId("different-request")
        .build();
    TriggerRequestEntity accepted = acceptedTriggerRequest();
    when(triggerRequests.selectByTenantAndRequestId(TENANT, REQUEST_ID)).thenReturn(accepted);
    when(workflowRuns.selectByTenantAndTriggerRequestId(TENANT, TRIGGER_REQUEST_ID))
        .thenReturn(null);
    when(replayOperations.selectByTargetRequest(TENANT, REQUEST_ID)).thenReturn(operation);

    assertThatThrownBy(() -> service.launch(transportRequest()))
        .isInstanceOf(BizException.class)
        .hasMessage("error.workflow.workflow_run_replay_approval_state_invalid");
    verify(replaySnapshotResolver, never()).resolve(operation, REQUEST_ID);
    verify(workflowRuns, never()).insertIfActiveSlotFree(org.mockito.ArgumentMatchers.any(WorkflowRunEntity.class));
  }

  @Test
  @DisplayName("活动运行导致等待时同时交付原请求和已审批执行输入，由等待服务统一保护")
  void shouldDeferApprovedSnapshot_whenAnotherRootRunOwnsTheBusinessDate() {
    ReplayOperationEntity operation = approvedOperation();
    WorkflowRunReplaySnapshotResolver.Snapshot snapshot = approvedSnapshot();
    TriggerRequestEntity accepted = acceptedTriggerRequest();
    when(triggerRequests.selectByTenantAndRequestId(TENANT, REQUEST_ID)).thenReturn(accepted);
    when(workflowRuns.selectByTenantAndTriggerRequestId(TENANT, TRIGGER_REQUEST_ID))
        .thenReturn(null, null, null, null);
    when(replayOperations.selectByTargetRequest(TENANT, REQUEST_ID)).thenReturn(operation);
    when(replaySnapshotResolver.resolve(operation, REQUEST_ID)).thenReturn(snapshot);
    when(triggerRequests.selectByTenantAndRequestIdForUpdate(TENANT, REQUEST_ID))
        .thenReturn(accepted);
    when(workflowRuns.insertIfActiveSlotFree(org.mockito.ArgumentMatchers.any(WorkflowRunEntity.class)))
        .thenReturn(0);

    service.launch(transportRequest());

    ArgumentCaptor<LaunchRequest> deferredCaptor = ArgumentCaptor.forClass(LaunchRequest.class);
    ArgumentCaptor<WorkflowLaunchWaitSnapshot> snapshotCaptor =
        ArgumentCaptor.forClass(WorkflowLaunchWaitSnapshot.class);
    verify(waitingLaunch).defer(deferredCaptor.capture(), snapshotCaptor.capture());
    assertThat(deferredCaptor.getValue().requestId()).isEqualTo(REQUEST_ID);
    assertThat(deferredCaptor.getValue().params()).isEmpty();
    assertThat(JsonUtils.toJson(deferredCaptor.getValue()))
        .doesNotContain("root-secret", "_rerunResultPolicy");
    WorkflowLaunchWaitSnapshot frozen = snapshotCaptor.getValue();
    assertThat(frozen.originalRequest()).isEqualTo(transportRequest());
    assertThat(frozen.effectiveRequest().params()).isEqualTo(snapshot.launchParams());
    assertThat(frozen.workflowVersion()).isEqualTo(snapshot.workflowVersion());
    assertThat(frozen.executionSnapshotJson()).isEqualTo(snapshot.executionSnapshotJson());
    verify(nodeDispatch, never()).dispatchNode(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyString());
  }

  @ParameterizedTest
  @CsvSource({
    "'{}',false,11",
    "'[]',false,11",
    "'{\"bundleFiles\":[{\"sourceFileId\":22,\"templateCode\":\"TEMPLATE\"}]}',false,22",
    "'{\"bundleFiles\":[{\"sourceFileId\":22,\"templateCode\":\"TEMPLATE\"}]}',true,33"
  })
  @DisplayName("根运行写入前按原覆盖顺序检查文件束来源，非对象节点参数不覆盖默认值")
  void shouldPreserveBundleParameterPrecedence_whenValidatingFileBeforeRun(
      String nodeParams, boolean rootOverrides, long expectedFileId) {
    TriggerRequestEntity accepted = acceptedTriggerRequest();
    WorkflowDefinitionEntity definition = new WorkflowDefinitionEntity(
        81L, TENANT, "BUNDLE_WORKFLOW", "Bundle workflow", "DAG", 1, true, null, "CALENDAR");
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeCode("IMPORT_FILES");
    node.setNodeType("JOB");
    node.setRelatedJobCode("BUNDLE_A");
    node.setEnabled(true);
    node.setNodeParams(nodeParams);
    Map<String, Object> defaultParams = Map.of(
        "bundleFiles", List.of(Map.of("sourceFileId", 11L, "templateCode", "TEMPLATE")));
    JobDefinitionEntity job = JobDefinitionEntity.builder()
        .id(700L)
        .tenantId(TENANT)
        .jobCode("BUNDLE_A")
        .jobType("BUNDLE_IMPORT")
        .defaultParams(defaultParams)
        .build();
    Map<String, Object> rootParams = rootOverrides
        ? Map.of("bundleFiles", List.of(Map.of("sourceFileId", 33L, "templateCode", "TEMPLATE")))
        : Map.of();
    LaunchRequest request = transportRequest().toBuilder().params(rootParams).build();
    String serializedParams = JsonUtils.toJson(rootParams);
    when(launchParams.resolveParentInstanceId(rootParams)).thenReturn(null);
    when(triggerRequests.selectByTenantAndRequestId(TENANT, REQUEST_ID)).thenReturn(accepted);
    when(triggerRequests.selectByTenantAndRequestIdForUpdate(TENANT, REQUEST_ID))
        .thenReturn(accepted);
    when(workflowDefinitions.selectByTenantAndIdAndEnabled(TENANT, 81L, true))
        .thenReturn(definition);
    when(graphValidator.validate(81L)).thenReturn(WorkflowValidationResult.clean());
    when(dagService.resolveInitialNodes(81L, serializedParams)).thenReturn(List.of());
    when(nodes.selectByWorkflowDefinitionId(81L)).thenReturn(List.of(node));
    when(configCache.findEnabledJobDefinition(TENANT, "BUNDLE_A")).thenReturn(job);
    when(rerunDefinitions.resolve(request, "BUNDLE_A", job)).thenReturn(job);
    String failure = "stop after selected file reference validation";
    doThrow(new IllegalStateException(failure)).when(fileReferences).validate(TENANT, expectedFileId);

    assertThatThrownBy(() -> service.launch(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(failure);

    verify(fileReferences).validate(TENANT, expectedFileId);
    verify(workflowRuns, never()).insertIfActiveSlotFree(any(WorkflowRunEntity.class));
    assertThat(job.defaultParams()).isEqualTo(defaultParams);
    assertThat(node.getNodeParams()).isEqualTo(nodeParams);
  }

  private ReplayOperationEntity approvedOperation() {
    return ReplayOperationEntity.builder()
        .id(808L)
        .tenantId(TENANT)
        .operationType("WORKFLOW_RUN")
        .status(ReplayOperationStatus.RUNNING.code())
        .scope("FULL_WORKFLOW")
        .executionMode("REPLAY")
        .targetRequestId(REQUEST_ID)
        .sourceWorkflowRunId(901L)
        .approvedBy("operator-b")
        .build();
  }

  private WorkflowRunReplaySnapshotResolver.Snapshot approvedSnapshot() {
    return new WorkflowRunReplaySnapshotResolver.Snapshot(
        TENANT,
        81L,
        4,
        "SOURCE_CALENDAR",
        BIZ_DATE,
        Instant.parse("2026-10-20T00:00:00Z"),
        Instant.parse("2026-10-21T00:00:00Z"),
        false,
        700L,
        Map.of("password", "root-secret", "_rerunResultPolicy", "CREATE_NEW_VERSION"),
        "{\"initialNodes\":[{\"nodeCode\":\"FIRST\",\"nodeType\":\"JOB\"}]}");
  }

  private TriggerRequestEntity acceptedTriggerRequest() {
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setId(TRIGGER_REQUEST_ID);
    request.setTenantId(TENANT);
    request.setRequestId(REQUEST_ID);
    request.setTriggerType(TriggerType.RERUN.code());
    request.setWorkflowDefinitionId(81L);
    request.setBizDate(BIZ_DATE);
    request.setRequestStatus(TriggerRequestStatus.ACCEPTED.code());
    request.setDryRun(false);
    request.setDedupKey(TENANT + ":workflow-replay:808");
    request.setTraceId("trace-replay-808");
    return request;
  }

  private LaunchRequest transportRequest() {
    return LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(81L)
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.RERUN)
        .requestId(REQUEST_ID)
        .traceId("trace-replay-808")
        .params(Map.of())
        .dataIntervalStart(Instant.parse("2026-10-20T00:00:00Z"))
        .dataIntervalEnd(Instant.parse("2026-10-21T00:00:00Z"))
        .dryRun(false)
        .build();
  }

  private PlatformTransactionManager noOpTransactions() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }
}
