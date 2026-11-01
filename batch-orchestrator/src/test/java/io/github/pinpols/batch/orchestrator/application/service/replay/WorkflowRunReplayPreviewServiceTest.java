package io.github.pinpols.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.DispatchTaskKeys;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ReplayOperationStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplaySourceResolver.Source;
import io.github.pinpols.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.controller.WorkflowRunReplayController;
import io.github.pinpols.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationPreviewTokenEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ReplayOperationStartParam;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.ApprovalCommandMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationPreviewTokenMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.orchestrator.service.DispatchChannelSnapshotService;
import io.github.pinpols.batch.orchestrator.service.DispatchSecretSnapshotResolver;
import io.github.pinpols.batch.orchestrator.service.FileTemplateSnapshotService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("整 Workflow 预览覆盖全部冻结节点、配置和副作用风险")
class WorkflowRunReplayPreviewServiceTest {

  private static final String TENANT = "tenant-replay-preview";
  private static final Long RUN_ID = 901L;
  private static final String JOB_CODE = "COMPUTE_A";

  @Mock
  private WorkflowRunReplaySourceResolver sourceResolver;

  @Mock
  private OrchestratorConfigCacheService configCache;

  @Mock
  private WorkflowRunMapper runs;

  @Mock
  private WorkflowNodeRunMapper nodeRuns;

  @Mock
  private JobInstanceMapper jobInstances;

  @Mock
  private FileTemplateSnapshotService fileTemplateSnapshots;

  @Mock
  private DispatchSecretSnapshotResolver dispatchSecretSnapshotResolver;

  @Mock
  private DispatchChannelSnapshotService dispatchChannelSnapshotService;

  @Mock
  private JobTaskMapper tasks;

  @Mock
  private WorkerRegistryMapper workers;

  @Mock
  private ReplayOperationPreviewTokenMapper previewTokens;

  @Mock
  private ReplayOperationMapper operations;

  @Mock
  private ApprovalCommandMapper approvals;

  @Mock
  private TriggerRequestMapper triggerRequests;

  @Mock
  private DomainEventPublisher domainEventPublisher;

  @Mock
  private WorkflowRunReplaySnapshotResolver snapshotResolver;

  @Mock
  private CrossDayDependencyResolver crossDayDependencyResolver;

  @Mock
  private SecretPayloadProtector secretPayloadProtector;

  @Mock
  private BatchDateTimeSupport dateTimeSupport;

  private WorkflowRunReplayPreviewService service;

  @BeforeEach
  void setUp() {
    // 保留真实定义与能力解析器，验证两者在预览链路中的集成。
    WorkflowReplayJobDefinitionResolver definitions = new WorkflowReplayJobDefinitionResolver(runs);
    WorkflowReplayCapabilityService capabilities = new WorkflowReplayCapabilityService(workers);
    Mockito.lenient()
        .when(dateTimeSupport.nowInstant())
        .thenReturn(Instant.parse("2026-10-23T00:00:00Z"));
    Mockito.lenient()
        .when(secretPayloadProtector.protect(ArgumentMatchers.anyString()))
        .thenReturn("sealed-preview-snapshot");
    service = new WorkflowRunReplayPreviewService(
        sourceResolver,
        definitions,
        capabilities,
        jobInstances,
        fileTemplateSnapshots,
        dispatchSecretSnapshotResolver,
        dispatchChannelSnapshotService,
        configCache,
        nodeRuns,
        tasks,
        previewTokens,
        operations,
        approvals,
        triggerRequests,
        domainEventPublisher,
        snapshotResolver,
        crossDayDependencyResolver,
        secretPayloadProtector,
        dateTimeSupport);
  }

  @Test
  @DisplayName("没有持久批准决策时，不解析快照或创建 Workflow Replay 请求和 Outbox")
  void shouldNotStartWholeWorkflowReplay_withoutPersistedApprovalDecision() {
    ReplayOperationEntity pending = pendingApprovalOperation();
    when(operations.selectForUpdate(TENANT, 808L)).thenReturn(pending);

    Throwable failure = catchThrowable(() -> service.approve(TENANT, 808L, "operator-b"));

    verifyNoInteractions(snapshotResolver, triggerRequests, domainEventPublisher);
    verify(operations, never())
        .startApprovedWorkflowRunReplay(ArgumentMatchers.any(ReplayOperationStartParam.class));
    assertThat(failure).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("没有持久拒绝决策时，不把仍待审批的 Workflow Replay Operation 改成拒绝")
  void shouldNotRejectWholeWorkflowReplay_withoutPersistedRejectionDecision() {
    ReplayOperationEntity pending = pendingApprovalOperation();
    when(operations.selectForUpdate(TENANT, 808L)).thenReturn(pending);

    Throwable failure = catchThrowable(() -> service.reject(TENANT, 808L));

    verify(operations, never())
        .rejectPendingApproval(
            ArgumentMatchers.eq(TENANT), ArgumentMatchers.eq(808L), ArgumentMatchers.any());
    verifyNoInteractions(snapshotResolver, triggerRequests, domainEventPublisher);
    assertThat(failure).isInstanceOf(BizException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "MISSING",
        "PENDING",
        "REJECTED",
        "WRONG_TENANT",
        "WRONG_KEY",
        "WRONG_TYPE",
        "WRONG_ACTION",
        "WRONG_TARGET_TYPE",
        "WRONG_TARGET_ID",
        "WRONG_PAYLOAD_TENANT",
        "WRONG_PAYLOAD_ID",
        "INVALID_JSON",
        "MISSING_APPROVER",
        "BLANK_APPROVER"
      })
  @DisplayName("批准决策必须绑定同租户、来源键、动作、目标、载荷及真实批准人，否则不创建请求和Outbox")
  void shouldNotStartWholeWorkflowReplay_whenPersistedDecisionDoesNotAuthorizeOperation(
      String scenario) {
    ReplayOperationEntity pending = pendingApprovalOperation();
    when(operations.selectForUpdate(TENANT, 808L)).thenReturn(pending);
    ApprovalCommandEntity decision = mismatchedReplayDecision(scenario, "APPROVED");
    when(approvals.selectByTenantAndSourceIdempotencyKey(
            TENANT, "7c51d5c7-afc4-4016-b8cf-1888939c1269"))
        .thenReturn(decision);

    Throwable failure = catchThrowable(() -> service.approve(TENANT, 808L, "request-actor"));

    verifyNoInteractions(snapshotResolver, triggerRequests, domainEventPublisher);
    verify(operations, never())
        .startApprovedWorkflowRunReplay(ArgumentMatchers.any(ReplayOperationStartParam.class));
    assertThat(failure).isInstanceOf(BizException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"MISSING", "APPROVED", "EXECUTED", "WRONG_PAYLOAD_ID"})
  @DisplayName("拒绝不能覆盖已批准或不属于此Operation的真实决策")
  void shouldNotRejectWholeWorkflowReplay_whenPersistedDecisionDoesNotAuthorizeRejection(
      String scenario) {
    ReplayOperationEntity pending = pendingApprovalOperation();
    when(operations.selectForUpdate(TENANT, 808L)).thenReturn(pending);
    ApprovalCommandEntity decision = mismatchedReplayDecision(scenario, "REJECTED");
    when(approvals.selectByTenantAndSourceIdempotencyKey(
            TENANT, "7c51d5c7-afc4-4016-b8cf-1888939c1269"))
        .thenReturn(decision);

    Throwable failure = catchThrowable(() -> service.reject(TENANT, 808L));

    verify(operations, never())
        .rejectPendingApproval(
            ArgumentMatchers.eq(TENANT), ArgumentMatchers.eq(808L), ArgumentMatchers.any());
    verifyNoInteractions(snapshotResolver, triggerRequests, domainEventPublisher);
    assertThat(failure).isInstanceOf(BizException.class);
  }

  private static ApprovalCommandEntity persistedReplayDecision(String status) {
    ApprovalCommandEntity decision = new ApprovalCommandEntity();
    decision.setTenantId(TENANT);
    decision.setApprovalNo("approval-workflow-808");
    decision.setApprovalType("COMPENSATION");
    decision.setActionType("WORKFLOW_RUN_REPLAY");
    decision.setTargetType("WORKFLOW_RUN");
    decision.setTargetId(String.valueOf(RUN_ID));
    decision.setSourceIdempotencyKey("7c51d5c7-afc4-4016-b8cf-1888939c1269");
    Map<String, Object> payload = Map.of("tenantId", TENANT, "replayOperationId", 808L);
    String payloadJson = JsonUtils.toJson(payload);
    decision.setPayloadJson(payloadJson);
    decision.setApprovalStatus(status);
    decision.setApproverId("operator-b");
    return decision;
  }

  private static ApprovalCommandEntity mismatchedReplayDecision(
      String scenario, String validStatus) {
    if ("MISSING".equals(scenario)) return null;
    ApprovalCommandEntity decision = persistedReplayDecision(validStatus);
    switch (scenario) {
      case "PENDING", "REJECTED", "APPROVED", "EXECUTED" -> decision.setApprovalStatus(scenario);
      case "WRONG_TENANT" -> decision.setTenantId("other-tenant");
      case "WRONG_KEY" -> decision.setSourceIdempotencyKey("another-operation");
      case "WRONG_TYPE" -> decision.setApprovalType("SELF_SERVICE");
      case "WRONG_ACTION" -> decision.setActionType("RERUN");
      case "WRONG_TARGET_TYPE" -> decision.setTargetType("JOB_INSTANCE");
      case "WRONG_TARGET_ID" -> decision.setTargetId("902");
      case "WRONG_PAYLOAD_TENANT" -> {
        Map<String, Object> payload = Map.of("tenantId", "other-tenant", "replayOperationId", 808L);
        String payloadJson = JsonUtils.toJson(payload);
        decision.setPayloadJson(payloadJson);
      }
      case "WRONG_PAYLOAD_ID" -> {
        Map<String, Object> payload = Map.of("tenantId", TENANT, "replayOperationId", 809L);
        String payloadJson = JsonUtils.toJson(payload);
        decision.setPayloadJson(payloadJson);
      }
      case "INVALID_JSON" -> decision.setPayloadJson("not-json");
      case "MISSING_APPROVER" -> decision.setApproverId(null);
      case "BLANK_APPROVER" -> decision.setApproverId(" ");
      default -> throw new IllegalArgumentException(scenario);
    }
    return decision;
  }

  private static ReplayOperationEntity pendingApprovalOperation() {
    ReplayOperationEntity operation = ReplayOperationEntity.builder()
        .id(808L)
        .tenantId(TENANT)
        .operationKey("7c51d5c7-afc4-4016-b8cf-1888939c1269")
        .operationType("WORKFLOW_RUN")
        .sourceWorkflowRunId(RUN_ID)
        .status(ReplayOperationStatus.PENDING_APPROVAL.code())
        .build();
    return operation;
  }

  @Test
  @DisplayName("完整图包含成功节点，根参数选择插件且不泄漏密钥参数")
  void shouldPreviewFullGraphAndUnknownSuccessfulNode_whenLatestPolicySelected() {
    Source source =
        source("ATOMIC", Map.of("taskType", "root-sql", "password", "private-root-secret"));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(configCache.findEnabledJobDefinition(TENANT, JOB_CODE))
        .thenReturn(definition("ATOMIC", 3), definition("ATOMIC", 4));
    WorkflowNodeRunEntity successfulNode = new WorkflowNodeRunEntity();
    successfulNode.setWorkflowRunId(RUN_ID);
    successfulNode.setNodeCode("FIRST");
    successfulNode.setNodeStatus("SUCCESS");
    successfulNode.setJobInstanceId(700L);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of(successfulNode));
    when(tasks.existsUnknownExternalOutcomeForJobInstance(TENANT, 700L)).thenReturn(true);
    stubDeclaredCatalogue();
    WorkflowRunReplayCommand command =
        command().toBuilder().configVersionPolicy("USE_LATEST_CONFIG").build();

    WorkflowRunReplayPreviewResponse result = service.preview(command);

    assertThat(result.workflowVersion()).isEqualTo(4);
    assertThat(result.bizDate()).isEqualTo(LocalDate.of(2026, 10, 20));
    assertThat(result.calendarCode()).isEqualTo("SOURCE_CALENDAR");
    assertThat(result.nodes())
        .extracting(WorkflowRunReplayPreviewResponse.NodePreview::nodeCode)
        .containsExactly("START", "FIRST", "SECOND", "END");
    assertThat(result.edges()).hasSize(3);
    var jobNodes =
        result.nodes().stream().filter(node -> "JOB".equals(node.nodeType())).toList();
    assertThat(jobNodes).allSatisfy(node -> {
      assertThat(node.jobDefinitionVersion()).isEqualTo(3);
      assertThat(node.pluginId()).isEqualTo("root-sql");
      assertThat(node.capability().status())
          .isEqualTo(WorkflowReplayCapabilityService.Status.DECLARED_CONSISTENT);
    });
    assertThat(jobNodes.getFirst().blockers()).contains("EXTERNAL_OUTCOME_UNKNOWN");
    assertThat(jobNodes.getLast().blockers()).doesNotContain("CLAIM_REPLAY_VALIDATION_UNAVAILABLE");
    assertThat(result.operationKey()).isNotNull();
    assertThat(result.previewToken()).isNotBlank();
    assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-10-23T00:05:00Z"));
    String json = JsonUtils.toJson(result);
    assertThat(json).doesNotContain("password", "private-root-secret", "protected-source-params");
  }

  @Test
  @DisplayName("预览凭证只保存令牌摘要并加密冻结快照")
  void shouldPersistHashedTokenAndSealedSnapshot_whenPreviewIsCreated() {
    Source source = source("ATOMIC", Map.of("password", "private-root-secret"));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            TENANT, "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of());

    WorkflowRunReplayPreviewResponse response = service.preview(command());

    var tokenCaptor = ArgumentCaptor.forClass(ReplayOperationPreviewTokenEntity.class);
    Mockito.verify(previewTokens).insert(tokenCaptor.capture());
    var storedToken = tokenCaptor.getValue();
    assertThat(storedToken.tokenHash()).isNotEqualTo(response.previewToken());
    assertThat(storedToken.operationKey()).isEqualTo(response.operationKey().toString());
    assertThat(storedToken.snapshotPayload())
        .contains("sealed-preview-snapshot")
        .doesNotContain("private-root-secret");
  }

  @Test
  @DisplayName("有效且未变化的整图预览提交为待审批 Replay Operation")
  void shouldPersistPendingWorkflowOperation_whenFreshPreviewTokenIsSubmitted() {
    Source source = source("ATOMIC", Map.of("taskType", "root-sql"));
    source.executionSnapshot().nodes().stream()
        .filter(node -> "JOB".equals(node.getNodeType()))
        .forEach(node -> node.setEnabled(false));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());

    WorkflowRunReplayPreviewResponse preview = service.preview(command());
    var previewTokenCaptor = ArgumentCaptor.forClass(ReplayOperationPreviewTokenEntity.class);
    Mockito.verify(previewTokens).insert(previewTokenCaptor.capture());
    var previewToken = previewTokenCaptor.getValue();
    when(previewTokens.selectForUpdate(TENANT, previewToken.tokenHash())).thenReturn(previewToken);
    when(previewTokens.consume(
            ArgumentMatchers.eq(TENANT),
            ArgumentMatchers.eq(previewToken.tokenHash()),
            ArgumentMatchers.any()))
        .thenReturn(1);
    when(previewTokens.linkOperation(
            ArgumentMatchers.eq(TENANT),
            ArgumentMatchers.eq(previewToken.tokenHash()),
            ArgumentMatchers.anyLong()))
        .thenReturn(1);
    ReplayOperationEntity persisted = ReplayOperationEntity.builder()
        .id(808L)
        .tenantId(TENANT)
        .operationKey(preview.operationKey().toString())
        .operationType("WORKFLOW_RUN")
        .status(ReplayOperationStatus.PENDING_APPROVAL.code())
        .requestHash(previewToken.requestHash())
        .build();
    when(operations.selectByIdentity(TENANT, preview.operationKey().toString()))
        .thenReturn(null, persisted);
    WorkflowRunReplayCommand submitCommand = command().toBuilder()
        .operationKey(preview.operationKey())
        .previewToken(preview.previewToken())
        .build();

    ReplayOperationEntity result = service.submitForApproval(submitCommand);

    assertThat(result.status()).isEqualTo(ReplayOperationStatus.PENDING_APPROVAL.code());
    var operationCaptor = ArgumentCaptor.forClass(ReplayOperationEntity.class);
    Mockito.verify(operations).insert(operationCaptor.capture());
    assertThat(operationCaptor.getValue().snapshotPayload())
        .isEqualTo(previewToken.snapshotPayload());
    assertThat(operationCaptor.getValue().sourceWorkflowRunId()).isEqualTo(RUN_ID);
  }

  @Test
  @DisplayName("静态能力目录在预览后变为 UNKNOWN 时拒绝提交并保留未消费凭证")
  void shouldRejectSubmission_whenSelectedPluginCapabilityBecomesUnknown() {
    Source source = source("ATOMIC", Map.of("taskType", "root-sql"));
    source.executionSnapshot().nodes().stream()
        .filter(node -> "SECOND".equals(node.getNodeCode()))
        .findFirst()
        .orElseThrow()
        .setEnabled(false);
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    String declaredCatalogue = JsonUtils.toJson(List.of(new ReplayCapabilityDeclaration(
        "root-sql",
        "1",
        List.of("BUSINESS_DATABASE"),
        IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC,
        "tenant+business-key",
        "SQL_V1",
        1)));
    String unknownCatalogue =
        JsonUtils.toJson(List.of(ReplayCapabilityDeclaration.unknown("root-sql")));
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            TENANT, "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of(declaredCatalogue))
        .thenReturn(List.of(unknownCatalogue));

    WorkflowRunReplayPreviewResponse preview = service.preview(command());
    var previewTokenCaptor = ArgumentCaptor.forClass(ReplayOperationPreviewTokenEntity.class);
    Mockito.verify(previewTokens).insert(previewTokenCaptor.capture());
    var previewToken = previewTokenCaptor.getValue();
    when(previewTokens.selectForUpdate(TENANT, previewToken.tokenHash())).thenReturn(previewToken);
    WorkflowRunReplayCommand submitCommand = command().toBuilder()
        .operationKey(preview.operationKey())
        .previewToken(preview.previewToken())
        .build();

    assertThatThrownBy(() -> service.submitForApproval(submitCommand))
        .isInstanceOf(BizException.class);

    Mockito.verify(previewTokens, Mockito.never())
        .consume(
            ArgumentMatchers.eq(TENANT),
            ArgumentMatchers.eq(previewToken.tokenHash()),
            ArgumentMatchers.any());
    Mockito.verify(operations, Mockito.never())
        .insert(ArgumentMatchers.any(ReplayOperationEntity.class));
  }

  @Test
  @DisplayName("静态能力目录一致时由 CLAIM 围栏承接运行时配置复核")
  void shouldNotAddUnavailableClaimBlockerWhenCapabilityIsDeclared() {
    Source source = source("ATOMIC", Map.of("taskType", "root-sql"));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    stubDeclaredCatalogue();

    WorkflowRunReplayPreviewResponse preview = service.preview(command());

    assertThat(preview.nodes().stream()
            .filter(node -> "JOB".equals(node.nodeType()))
            .flatMap(node -> node.blockers().stream()))
        .doesNotContain("CLAIM_REPLAY_VALIDATION_UNAVAILABLE");
  }

  @Test
  @DisplayName("预览凭证过期后不创建 Replay Operation")
  void shouldRejectExpiredPreviewToken_beforeCreatingOperation() {
    Source source = source("ATOMIC", Map.of("taskType", "root-sql"));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    stubDeclaredCatalogue();
    Instant createdAt = Instant.parse("2026-10-23T00:00:00Z");
    when(dateTimeSupport.nowInstant()).thenReturn(createdAt, createdAt.plusSeconds(301));

    WorkflowRunReplayPreviewResponse preview = service.preview(command());
    var tokenCaptor = ArgumentCaptor.forClass(ReplayOperationPreviewTokenEntity.class);
    Mockito.verify(previewTokens).insert(tokenCaptor.capture());
    var token = tokenCaptor.getValue();
    when(previewTokens.selectForUpdate(TENANT, token.tokenHash())).thenReturn(token);
    WorkflowRunReplayCommand submitCommand = command().toBuilder()
        .operationKey(preview.operationKey())
        .previewToken(preview.previewToken())
        .build();

    assertThatThrownBy(() -> service.submitForApproval(submitCommand))
        .isInstanceOf(BizException.class);

    Mockito.verify(operations, Mockito.never())
        .insert(ArgumentMatchers.any(ReplayOperationEntity.class));
    Mockito.verify(previewTokens, Mockito.never())
        .consume(
            ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
  }

  @Test
  @DisplayName("内部 HTTP 拒绝待审批的整 Workflow 操作并幂等返回已拒绝状态")
  void shouldRejectPendingWorkflowOperationOverHttpIdempotently() throws Exception {
    ReplayOperationEntity pending = ReplayOperationEntity.builder()
        .id(808L)
        .tenantId(TENANT)
        .operationKey("7c51d5c7-afc4-4016-b8cf-1888939c1269")
        .sourceWorkflowRunId(RUN_ID)
        .operationType("WORKFLOW_RUN")
        .status(ReplayOperationStatus.PENDING_APPROVAL.code())
        .build();
    ReplayOperationEntity rejected =
        pending.toBuilder().status(ReplayOperationStatus.REJECTED.code()).build();
    ApprovalCommandEntity decision = persistedReplayDecision("REJECTED");
    when(approvals.selectByTenantAndSourceIdempotencyKey(
            TENANT, "7c51d5c7-afc4-4016-b8cf-1888939c1269"))
        .thenReturn(decision);
    when(operations.selectForUpdate(TENANT, 808L)).thenReturn(pending, rejected);
    when(operations.rejectPendingApproval(
            ArgumentMatchers.eq(TENANT), ArgumentMatchers.eq(808L), ArgumentMatchers.any()))
        .thenReturn(1);
    when(operations.selectById(TENANT, 808L)).thenReturn(rejected);

    WorkflowRunReplayController controller = new WorkflowRunReplayController(service);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
    String body = JsonUtils.toJson(Map.of("tenantId", TENANT));
    var rejectRequest = post("/internal/orchestrator/workflow-replay/operations/808/reject")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);

    mvc.perform(rejectRequest)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(808))
        .andExpect(jsonPath("$.data.status").value(ReplayOperationStatus.REJECTED.code()));
    mvc.perform(post("/internal/orchestrator/workflow-replay/operations/808/reject")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value(ReplayOperationStatus.REJECTED.code()));

    Mockito.verify(operations)
        .rejectPendingApproval(
            ArgumentMatchers.eq(TENANT), ArgumentMatchers.eq(808L), ArgumentMatchers.any());
  }

  @Test
  @DisplayName("审批原子创建稳定 TriggerRequest 和 Outbox 事件且不暴露根密钥参数")
  void shouldApproveWholeWorkflowReplayThroughOutboxWithoutPublishingSecrets() throws Exception {
    Instant approvedAt = Instant.parse("2026-10-23T00:00:00Z");
    ReplayOperationEntity pending = ReplayOperationEntity.builder()
        .id(808L)
        .tenantId(TENANT)
        .operationKey("7c51d5c7-afc4-4016-b8cf-1888939c1269")
        .sourceWorkflowRunId(RUN_ID)
        .operationType("WORKFLOW_RUN")
        .status(ReplayOperationStatus.PENDING_APPROVAL.code())
        .traceId("trace-replay-808")
        .build();
    ReplayOperationEntity running = pending.toBuilder()
        .status(ReplayOperationStatus.RUNNING.code())
        .approvedBy("operator-b")
        .targetRequestId("workflow-replay-808")
        .build();
    var snapshot = new WorkflowRunReplaySnapshotResolver.Snapshot(
        TENANT,
        81L,
        4,
        "SOURCE_CALENDAR",
        LocalDate.of(2026, 10, 20),
        Instant.parse("2026-10-20T00:00:00Z"),
        Instant.parse("2026-10-21T00:00:00Z"),
        false,
        null,
        Map.of("password", "root-private-secret"),
        "frozen-execution-snapshot");
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(81L)
        .bizDate(LocalDate.of(2026, 10, 20))
        .triggerType(TriggerType.RERUN)
        .requestId("workflow-replay-808")
        .traceId("trace-replay-808")
        .params(Map.of())
        .dataIntervalStart(Instant.parse("2026-10-20T00:00:00Z"))
        .dataIntervalEnd(Instant.parse("2026-10-21T00:00:00Z"))
        .dryRun(false)
        .build();
    TriggerRequestEntity accepted = new TriggerRequestEntity();
    accepted.setId(909L);
    accepted.setTenantId(TENANT);
    accepted.setRequestId("workflow-replay-808");
    accepted.setTriggerType(TriggerType.RERUN.code());
    accepted.setWorkflowDefinitionId(81L);
    accepted.setBizDate(LocalDate.of(2026, 10, 20));
    accepted.setDedupKey(TENANT + ":workflow-replay:808");
    accepted.setRequestStatus("ACCEPTED");
    accepted.setTraceId("trace-replay-808");
    accepted.setDryRun(false);
    accepted.setLaunchPayload(JsonUtils.toJson(launchRequest));
    accepted.setCreatedAt(approvedAt);
    ApprovalCommandEntity decision = persistedReplayDecision("APPROVED");
    when(approvals.selectByTenantAndSourceIdempotencyKey(
            TENANT, "7c51d5c7-afc4-4016-b8cf-1888939c1269"))
        .thenReturn(decision);
    when(operations.selectForUpdate(TENANT, 808L)).thenReturn(pending);
    when(snapshotResolver.resolve(pending, "workflow-replay-808")).thenReturn(snapshot);
    when(dateTimeSupport.nowInstant()).thenReturn(approvedAt);
    when(operations.startApprovedWorkflowRunReplay(
            ArgumentMatchers.any(ReplayOperationStartParam.class)))
        .thenReturn(1);
    when(triggerRequests.insert(ArgumentMatchers.any(TriggerRequestEntity.class)))
        .thenReturn(1);
    when(triggerRequests.selectByTenantAndRequestId(TENANT, "workflow-replay-808"))
        .thenReturn(accepted);
    when(operations.selectById(TENANT, 808L)).thenReturn(running);

    WorkflowRunReplayController controller = new WorkflowRunReplayController(service);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
    String body = JsonUtils.toJson(Map.of("tenantId", TENANT, "approver", "request-actor"));

    mvc.perform(post("/internal/orchestrator/workflow-replay/operations/808/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value(ReplayOperationStatus.RUNNING.code()))
        .andExpect(jsonPath("$.data.approvedBy").value("operator-b"));

    var triggerCaptor = ArgumentCaptor.forClass(TriggerRequestEntity.class);
    Mockito.verify(triggerRequests).insert(triggerCaptor.capture());
    assertThat(triggerCaptor.getValue().getRequestId()).isEqualTo("workflow-replay-808");
    assertThat(triggerCaptor.getValue().getLaunchPayload())
        .doesNotContain("root-private-secret", "password")
        .contains("\"workflowDefinitionId\":81", "\"params\":{}")
        .doesNotContain("frozen-execution-snapshot");
    var eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
    Mockito.verify(domainEventPublisher).publish(eventCaptor.capture());
    assertThat(JsonUtils.toJson(eventCaptor.getValue()))
        .contains("workflow-replay-808", "WORKFLOW_LAUNCH")
        .doesNotContain("root-private-secret", "password");
    var startCaptor = ArgumentCaptor.forClass(ReplayOperationStartParam.class);
    Mockito.verify(operations).startApprovedWorkflowRunReplay(startCaptor.capture());
    assertThat(startCaptor.getValue().approver()).isEqualTo("operator-b");
  }

  @Test
  @DisplayName("已审批的同一稳定请求重复审批时幂等返回且不重复派发")
  void shouldReturnAlreadyApprovedWholeWorkflowReplayWithoutRepublishing() {
    ReplayOperationEntity running = ReplayOperationEntity.builder()
        .id(808L)
        .tenantId(TENANT)
        .operationKey("7c51d5c7-afc4-4016-b8cf-1888939c1269")
        .sourceWorkflowRunId(RUN_ID)
        .operationType("WORKFLOW_RUN")
        .status(ReplayOperationStatus.RUNNING.code())
        .approvedBy("operator-b")
        .targetRequestId("workflow-replay-808")
        .build();
    ApprovalCommandEntity decision = persistedReplayDecision("APPROVED");
    when(approvals.selectByTenantAndSourceIdempotencyKey(
            TENANT, "7c51d5c7-afc4-4016-b8cf-1888939c1269"))
        .thenReturn(decision);
    when(operations.selectForUpdate(TENANT, 808L)).thenReturn(running);

    ReplayOperationEntity result = service.approve(TENANT, 808L, "retrying-admin");

    assertThat(result).isSameAs(running);
    verifyNoInteractions(snapshotResolver, triggerRequests, domainEventPublisher);
  }

  @Test
  @DisplayName("未固定的文件模板配置阻止从类型或根参数猜测插件")
  void shouldExposeUnfrozenPluginSelection_whenFileTemplateConfigurationIsMissing() {
    Source source = source("IMPORT", Map.of("taskType", "root-sql"));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());

    WorkflowRunReplayPreviewResponse result = service.preview(command());

    assertThat(result.nodes().get(1).blockers()).contains("PLUGIN_SELECTION_NOT_FROZEN");
    assertThat(result.nodes().get(1).pluginId()).isNull();
    verifyNoInteractions(workers, configCache);
  }

  @Test
  @DisplayName("文件作业预览基于捕获的模板快照选择插件并冻结同一密文")
  void shouldSelectImportPluginFromCapturedTemplateAndFreezeIt() {
    Source source = source("IMPORT", Map.of("templateCode", "TPL_A"));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(configCache.findEnabledJobDefinition(TENANT, JOB_CODE))
        .thenReturn(definition("IMPORT", 4));
    when(fileTemplateSnapshots.captureForWorkflowReplay(
            ArgumentMatchers.eq(TENANT),
            ArgumentMatchers.any(JobDefinitionView.class),
            ArgumentMatchers.anyMap(),
            ArgumentMatchers.eq(ConfigVersionPolicy.USE_LATEST_CONFIG),
            ArgumentMatchers.isNull()))
        .thenReturn(new FileTemplateSnapshotService.ReplayTemplateSelection(
            Map.of("TPL_A", "sealed-TPL_A"), "jdbc_mapped"));
    stubDeclaredCatalogue();

    WorkflowRunReplayCommand latestCommand =
        command().toBuilder().configVersionPolicy("USE_LATEST_CONFIG").build();
    WorkflowRunReplayPreviewResponse result = service.preview(latestCommand);

    assertThat(result.nodes().get(1).pluginId()).isEqualTo("jdbc_mapped");
    assertThat(result.nodes().get(1).blockers())
        .doesNotContain(
            "CLAIM_REPLAY_VALIDATION_UNAVAILABLE",
            "PLUGIN_SELECTION_NOT_FROZEN",
            "TEMPLATE_CONFIGURATION_NOT_FROZEN");
    ArgumentCaptor<String> snapshotCaptor = ArgumentCaptor.forClass(String.class);
    Mockito.verify(secretPayloadProtector).protect(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue())
        .contains("selectedFileTemplateSnapshots", "sealed-TPL_A")
        .doesNotContain("templateCode");
  }

  @Test
  @DisplayName("分发作业预览固定选中的通道配置和密钥版本绑定")
  void shouldFreezeDispatchChannelAndSecretBindings_whenLatestConfigSelected() {
    Source source = source(
        "DISPATCH",
        Map.of("dispatchChannelCode", "CHANNEL_A", "password", "private-source-secret"));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(configCache.findEnabledJobDefinition(TENANT, JOB_CODE))
        .thenReturn(definition("DISPATCH", 5));
    Map<String, Object> secretBinding =
        Map.of("secretRef", "dispatch/key", "secretVersionId", 72L, "versionNo", 4);
    Map<String, Object> secretBindings = Map.of("CHANNEL_A", secretBinding);
    Map<String, Object> effectiveParams =
        new LinkedHashMap<>(definition("DISPATCH", 5).defaultParams());
    effectiveParams.putAll(source.rootParams());
    Map<String, Object> pinnedParams = new LinkedHashMap<>(source.rootParams());
    pinnedParams.put(DispatchTaskKeys.SECRET_BINDINGS, secretBindings);
    when(dispatchSecretSnapshotResolver.pin(
            ArgumentMatchers.eq(TENANT),
            ArgumentMatchers.eq(JobType.DISPATCH.code()),
            ArgumentMatchers.anyMap()))
        .thenReturn(pinnedParams);
    DispatchChannelSnapshotService.ReplayDispatchCaptureRequest expectedCaptureRequest =
        new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
            TENANT,
            JobType.DISPATCH.code(),
            effectiveParams,
            ConfigVersionPolicy.USE_LATEST_CONFIG,
            null,
            secretBindings);
    when(dispatchChannelSnapshotService.captureForWorkflowReplay(expectedCaptureRequest))
        .thenReturn(new DispatchChannelSnapshotService.ReplayDispatchSelection(
            Map.of("CHANNEL_A", "sealed-channel-a"), secretBindings, JobType.DISPATCH.code()));

    WorkflowRunReplayCommand latestCommand = command().toBuilder()
        .configVersionPolicy(ConfigVersionPolicy.USE_LATEST_CONFIG.code())
        .build();
    WorkflowRunReplayPreviewResponse result = service.preview(latestCommand);

    assertThat(result.nodes().get(1).pluginId()).isEqualTo(JobType.DISPATCH.code());
    assertThat(result.nodes().get(1).blockers())
        .doesNotContain(
            "CLAIM_REPLAY_VALIDATION_UNAVAILABLE",
            "CONFIGURATION_UNAVAILABLE",
            "PLUGIN_SELECTION_NOT_FROZEN");
    ArgumentCaptor<String> snapshotCaptor = ArgumentCaptor.forClass(String.class);
    Mockito.verify(secretPayloadProtector).protect(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue())
        .contains("selectedDispatchChannelSnapshots", "sealed-channel-a")
        .contains("selectedDispatchSecretBindings", "dispatch/key", "secretVersionId")
        .doesNotContain("private-source-secret");
    Mockito.verify(dispatchChannelSnapshotService, Mockito.times(2))
        .captureForWorkflowReplay(expectedCaptureRequest);
  }

  @Test
  @DisplayName("原配置策略固定来源版本，空在线目录显示阻塞")
  void shouldKeepOriginalDefinitions_whenCurrentConfigurationHasChanged() {
    Source source = source("ATOMIC", Map.of("taskType", "root-sql"));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            TENANT, "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of());

    WorkflowRunReplayPreviewResponse result = service.preview(command());

    assertThat(result.nodes().get(1).jobDefinitionVersion()).isEqualTo(2);
    assertThat(result.nodes().get(1).blockers()).contains("NO_ONLINE_WORKERS");
    verifyNoInteractions(configCache);
  }

  @Test
  @DisplayName("依赖未来输出的动态插件身份不能冒充已固定")
  void shouldExposeUnfrozenSelection_whenPluginDependsOnFutureNodeOutput() {
    Source source = source("ATOMIC", Map.of("taskType", "$.nodes.FIRST.output.executor"));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());

    WorkflowRunReplayPreviewResponse result = service.preview(command());

    assertThat(result.nodes().get(1).blockers()).contains("PLUGIN_SELECTION_NOT_FROZEN");
    verifyNoInteractions(workers);
  }

  @Test
  @DisplayName("无有效执行器子类型时按实际 Worker 路由回退到任务类型")
  void shouldMatchWorkerExecutorFallback_whenSubtypeIsNotAString() {
    Source source = source("ATOMIC", Map.of("taskType", 123));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            TENANT, "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of());

    WorkflowRunReplayPreviewResponse result = service.preview(command());

    assertThat(result.nodes().get(1).pluginId()).isEqualTo("ATOMIC");
    assertThat(result.nodes().get(1).blockers()).contains("NO_ONLINE_WORKERS");
  }

  @Test
  @DisplayName("跨日依赖在结果版本不可用时显示对应节点阻塞")
  void shouldExposeDependencyBlocker_whenResultVersionsCannotBeResolved() {
    Source source = source("ATOMIC", Map.of("taskType", "root-sql"));
    String dependencyJson =
        "[{\"alias\":\"previous\",\"jobCode\":\"UPSTREAM\",\"bizDateOffset\":-1}]";
    source.executionSnapshot().nodes().get(1).setCrossDayDependencies(dependencyJson);
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(crossDayDependencyResolver.resolve(
            TENANT, LocalDate.of(2026, 10, 20), dependencyJson, false))
        .thenReturn(CrossDayDependencyResolver.ResolutionResult.builder()
            .status(CrossDayDependencyResolver.ResolutionStatus.WAITING)
            .resolved(Map.of())
            .waitingReasons(List.of("MISSING"))
            .resultVersionIdsByAlias(Map.of("previous", List.of()))
            .build());
    stubDeclaredCatalogue();

    WorkflowRunReplayPreviewResponse result = service.preview(command());

    assertThat(result.nodes().get(1).blockers()).contains("DEPENDENCY_RESULTS_NOT_PINNED");
  }

  @Test
  @DisplayName("整 Workflow 重放预览将跨日结果版本固定进受保护快照")
  void shouldFreezeCrossDayResultVersionIds_whenDependencyIsResolved() {
    Source source = source("ATOMIC", Map.of("taskType", "root-sql"));
    String dependencyJson =
        "[{\"alias\":\"previous\",\"jobCode\":\"UPSTREAM\",\"bizDateOffset\":-1}]";
    source.executionSnapshot().nodes().get(1).setCrossDayDependencies(dependencyJson);
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    when(crossDayDependencyResolver.resolve(
            TENANT, LocalDate.of(2026, 10, 20), dependencyJson, false))
        .thenReturn(CrossDayDependencyResolver.ResolutionResult.builder()
            .status(CrossDayDependencyResolver.ResolutionStatus.RESOLVED)
            .resolved(Map.of("previous", Map.of("resultVersionId", 771L)))
            .waitingReasons(List.of())
            .resultVersionIdsByAlias(Map.of("previous", List.of(771L)))
            .build());
    stubDeclaredCatalogue();

    WorkflowRunReplayPreviewResponse result = service.preview(command());

    assertThat(result.nodes().get(1).blockers()).doesNotContain("DEPENDENCY_RESULTS_NOT_PINNED");
    var payloadCaptor = ArgumentCaptor.forClass(String.class);
    Mockito.verify(secretPayloadProtector).protect(payloadCaptor.capture());
    Map<String, Object> payload =
        JsonUtils.fromJson(payloadCaptor.getValue(), new TypeReference<Map<String, Object>>() {});
    Map<?, ?> idsByNode =
        (Map<?, ?>) payload.get("selectedCrossDayDependencyResultVersionIdsByNode");
    assertThat(idsByNode.get("FIRST")).isEqualTo(Map.of("previous", List.of(771)));
  }

  @Test
  @DisplayName("非法指定版本在读取来源之前拒绝")
  void shouldRejectInvalidPolicy_beforeReadingSource() {
    WorkflowRunReplayCommand command =
        command().toBuilder().configVersionPolicy("USE_SPECIFIED_VERSION").build();

    assertThatThrownBy(() -> service.preview(command)).isInstanceOf(BizException.class);

    verifyNoInteractions(sourceResolver, nodeRuns, tasks, workers, configCache);
  }

  @Test
  @DisplayName("内部 HTTP 入口反序列化策略并返回完整图和节点阻塞")
  void shouldReturnFrozenGraphPreview_whenInternalHttpRequestArrives() throws Exception {
    Source source = source("IMPORT", Map.of("password", "private-root-secret"));
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    WorkflowRunReplayController controller = new WorkflowRunReplayController(service);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
    String body = JsonUtils.toJson(command());
    var request = post("/internal/orchestrator/workflow-replay/preview")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);

    mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sourceWorkflowRunId").value(901))
        .andExpect(jsonPath("$.data.nodes.length()").value(4))
        .andExpect(jsonPath("$.data.nodes[1].blockers")
            .value(Matchers.not(Matchers.hasItem("CLAIM_REPLAY_VALIDATION_UNAVAILABLE"))))
        .andExpect(jsonPath("$.data.previewToken").isNotEmpty())
        .andExpect(jsonPath("$.data.rootParams").doesNotExist());
  }

  @Test
  @DisplayName("来源节点读取异常为空时失败关闭")
  void shouldRejectPreview_whenSourceNodeReadIsUnavailable() {
    Source source = source("ATOMIC", Map.of());
    when(sourceResolver.resolve(TENANT, RUN_ID)).thenReturn(source);
    when(nodeRuns.selectByWorkflowRunId(RUN_ID)).thenReturn(null);

    assertThatThrownBy(() -> service.preview(command())).isInstanceOf(BizException.class);

    verifyNoInteractions(tasks, workers, configCache);
  }

  private void stubDeclaredCatalogue() {
    ReplayCapabilityDeclaration declaration = new ReplayCapabilityDeclaration(
        "root-sql",
        "1",
        List.of("BUSINESS_DATABASE"),
        IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC,
        "tenant+business-key",
        "SQL_V1",
        1);
    String catalogue = JsonUtils.toJson(List.of(declaration));
    when(workers.selectReplayCapabilitiesByTenantAndWorkerGroup(
            TENANT, "ATOMIC", List.of("ONLINE")))
        .thenReturn(List.of(catalogue));
  }

  private Source source(String jobType, Map<String, Object> rootParams) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(RUN_ID);
    run.setTenantId(TENANT);
    run.setBizDate(LocalDate.of(2026, 10, 20));
    JobDefinitionView definition = definition(jobType, 2);
    List<WorkflowNodeEntity> nodes = List.of(
        node("START", "START"), node("FIRST", "JOB"), node("SECOND", "JOB"), node("END", "END"));
    List<WorkflowEdgeEntity> edges =
        List.of(edge("START", "FIRST"), edge("FIRST", "SECOND"), edge("SECOND", "END"));
    WorkflowRunExecutionSnapshot snapshot = new WorkflowRunExecutionSnapshot(
        2,
        81L,
        4,
        "SOURCE_CALENDAR",
        null,
        null,
        nodes,
        edges,
        Map.of("FIRST", definition, "SECOND", definition),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of());
    return new Source(run, snapshot, "protected-source-params", rootParams);
  }

  private JobDefinitionView definition(String jobType, int version) {
    return JobDefinitionView.builder()
        .id(101L)
        .tenantId(TENANT)
        .jobCode(JOB_CODE)
        .jobType(jobType)
        .version(version)
        .workerGroup("ATOMIC")
        .defaultParams(Map.of("taskType", "definition-sql"))
        .build();
  }

  private WorkflowNodeEntity node(String code, String type) {
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setTenantId(TENANT);
    node.setWorkflowDefinitionId(81L);
    node.setNodeCode(code);
    node.setNodeType(type);
    node.setEnabled(true);
    if ("JOB".equals(type)) {
      node.setRelatedJobCode(JOB_CODE);
      node.setNodeParams("{\"taskType\":\"node-sql\"}");
    }
    return node;
  }

  private WorkflowEdgeEntity edge(String from, String to) {
    WorkflowEdgeEntity edge = new WorkflowEdgeEntity();
    edge.setFromNodeCode(from);
    edge.setToNodeCode(to);
    edge.setEdgeType("SUCCESS");
    edge.setEnabled(true);
    return edge;
  }

  private WorkflowRunReplayCommand command() {
    return WorkflowRunReplayCommand.builder()
        .tenantId(TENANT)
        .workflowRunId(RUN_ID)
        .reason("重新计算业务日")
        .requestedBy("operator-a")
        .build();
  }
}
