package io.github.pinpols.batch.orchestrator.application.service.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ApprovalCommandStatus;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.DispatchTaskKeys;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ReplayOperationStatus;
import io.github.pinpols.batch.common.enums.ReplayOperationType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.ResultVersionPolicy;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplayPreviewResponse.EdgePreview;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplayPreviewResponse.NodePreview;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplaySourceResolver.Source;
import io.github.pinpols.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver;
import io.github.pinpols.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationPreviewTokenEntity;
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
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.service.DispatchChannelSnapshotService;
import io.github.pinpols.batch.orchestrator.service.DispatchSecretSnapshotResolver;
import io.github.pinpols.batch.orchestrator.service.FileTemplateSnapshotService;
import io.github.pinpols.batch.orchestrator.service.LaunchRequestIdentityGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 整 Workflow 重放准入前检查全部来源图节点。 */
@Service
@RequiredArgsConstructor
public class WorkflowRunReplayPreviewService {

  private static final Duration PREVIEW_TOKEN_TTL = Duration.ofMinutes(5);
  private static final String WORKFLOW_REPLAY_REQUEST_PREFIX = "workflow-replay-";
  private static final String WORKFLOW_REPLAY_DEDUP_PREFIX = ":workflow-replay:";

  private final WorkflowRunReplaySourceResolver sourceResolver;
  private final WorkflowReplayJobDefinitionResolver definitionResolver;
  private final WorkflowReplayCapabilityService capabilityService;
  private final JobInstanceMapper jobInstanceMapper;
  private final FileTemplateSnapshotService fileTemplateSnapshotService;
  private final DispatchSecretSnapshotResolver dispatchSecretSnapshotResolver;
  private final DispatchChannelSnapshotService dispatchChannelSnapshotService;
  private final OrchestratorConfigCacheService configCacheService;
  private final WorkflowNodeRunMapper nodeRunMapper;
  private final JobTaskMapper jobTaskMapper;
  private final ReplayOperationPreviewTokenMapper tokenMapper;
  private final ReplayOperationMapper operationMapper;
  private final ApprovalCommandMapper approvalCommandMapper;
  private final TriggerRequestMapper triggerRequestMapper;
  private final DomainEventPublisher domainEventPublisher;
  private final WorkflowRunReplaySnapshotResolver snapshotResolver;
  private final CrossDayDependencyResolver crossDayDependencyResolver;
  private final SecretPayloadProtector secretPayloadProtector;
  private final BatchDateTimeSupport dateTimeSupport;

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public WorkflowRunReplayPreviewResponse preview(WorkflowRunReplayCommand command) {
    UUID operationKey = UUID.randomUUID();
    String rawToken = UUID.randomUUID().toString();
    Instant now = dateTimeSupport.nowInstant();
    Instant expiresAt = now.plus(PREVIEW_TOKEN_TTL);
    PreparedPreview prepared = preparePreview(command, operationKey, rawToken, expiresAt);
    WorkflowRunReplayPreviewResponse response = prepared.response();
    String tokenHash = sha256(rawToken);
    String protectedSnapshot = secretPayloadProtector.protect(prepared.snapshotPayload());
    tokenMapper.setTenantContext(command.tenantId());
    ReplayOperationPreviewTokenMapper previewTokenMapper = tokenMapper;
    ReplayOperationPreviewTokenEntity.ReplayOperationPreviewTokenEntityBuilder previewTokenBuilder =
        ReplayOperationPreviewTokenEntity.builder();
    previewTokenBuilder.tenantId(command.tenantId());
    previewTokenBuilder.tokenHash(tokenHash);
    previewTokenBuilder.operationKey(operationKey.toString());
    String replayRequestHash = requestHash(command, prepared.policies());
    previewTokenBuilder.requestHash(replayRequestHash);
    String snapshotHash = sha256(prepared.snapshotPayload());
    previewTokenBuilder.snapshotHash(snapshotHash);
    String serializedProtectedSnapshot =
        JsonUtils.toJson(Map.of("sealedSnapshotPayload", protectedSnapshot));
    previewTokenBuilder.snapshotPayload(serializedProtectedSnapshot);
    previewTokenBuilder.expiresAt(expiresAt);
    previewTokenBuilder.createdAt(now);
    previewTokenMapper.insert(previewTokenBuilder.build());
    return response;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public ReplayOperationEntity submitForApproval(WorkflowRunReplayCommand command) {
    Policies policies = validate(command);
    if (EmptyChecks.isNull(command.operationKey()) || EmptyChecks.isBlank(command.previewToken())) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.workflow.workflow_run_replay_preview_required");
    }
    operationMapper.setTenantContext(command.tenantId());
    tokenMapper.setTenantContext(command.tenantId());
    String operationKey = command.operationKey().toString();
    String requestHash = requestHash(command, policies);
    ReplayOperationEntity existing =
        operationMapper.selectByIdentity(command.tenantId(), operationKey);
    if (EmptyChecks.isNotNull(existing)) {
      if (requestHash.equals(existing.requestHash())
          && ReplayOperationType.WORKFLOW_RUN.code().equals(existing.operationType())) {
        return existing;
      }
      throw previewChanged();
    }

    String tokenHash = sha256(command.previewToken());
    ReplayOperationPreviewTokenEntity token;
    try {
      token = tokenMapper.selectForUpdate(command.tenantId(), tokenHash);
    } catch (PessimisticLockingFailureException concurrentConsumption) {
      throw previewChanged();
    }
    boolean previewTokenIdentityDoesNotMatch = EmptyChecks.isNull(token)
        || !operationKey.equals(token.operationKey())
        || !requestHash.equals(token.requestHash());
    if (previewTokenIdentityDoesNotMatch) {
      throw previewChanged();
    }
    if (EmptyChecks.isNotNull(token.consumedAt())) {
      ReplayOperationEntity consumed = EmptyChecks.isNull(token.operationId())
          ? null
          : operationMapper.selectById(command.tenantId(), token.operationId());
      if (EmptyChecks.isNotNull(consumed) && requestHash.equals(consumed.requestHash())) {
        return consumed;
      }
      throw previewChanged();
    }
    Instant now = dateTimeSupport.nowInstant();
    if (EmptyChecks.isNull(token.expiresAt()) || !token.expiresAt().isAfter(now)) {
      throw previewChanged();
    }

    PreparedPreview fresh =
        preparePreview(command, command.operationKey(), command.previewToken(), token.expiresAt());
    String freshSnapshotHash = sha256(fresh.snapshotPayload());
    if (!freshSnapshotHash.equals(token.snapshotHash())
        || fresh.response().nodes().stream()
            .anyMatch(node -> !EmptyChecks.isEmpty(Objects.requireNonNull(node.blockers())))) {
      throw previewChanged();
    }
    if (tokenMapper.consume(command.tenantId(), tokenHash, now) != 1) {
      throw previewChanged();
    }

    Source source = fresh.source();
    ReplayOperationEntity.ReplayOperationEntityBuilder operationBuilder =
        ReplayOperationEntity.builder()
            .tenantId(command.tenantId())
            .operationKey(operationKey)
            .operationType(ReplayOperationType.WORKFLOW_RUN.code())
            .status(ReplayOperationStatus.PENDING_APPROVAL.code())
            .calendarCode(source.executionSnapshot().workflowCalendarCode())
            .scope("FULL_WORKFLOW")
            .executionMode("REPLAY")
            .candidateSource("EXACT_SOURCE_WORKFLOW_RUN");
    Map<String, Object> scopePayload =
        Map.of("sourceWorkflowRunId", source.run().getId());
    String scopePayloadJson = JsonUtils.toJson(scopePayload);
    operationBuilder
        .scopePayload(scopePayloadJson)
        .resultPolicy(policies.result().code())
        .configVersionPolicy(policies.config().code())
        .configVersion(command.configVersion())
        .sourceWorkflowRunId(source.run().getId())
        .requestHash(requestHash)
        .snapshotHash(token.snapshotHash())
        .snapshotPayload(token.snapshotPayload());
    String normalizedReason = command.reason().trim();
    operationBuilder.reason(normalizedReason);
    String normalizedRequestedBy = command.requestedBy().trim();
    operationBuilder.requestedBy(normalizedRequestedBy);
    ReplayOperationEntity operation = operationBuilder
        .totalCount(1)
        .succeededCount(0)
        .failedCount(0)
        .inFlightCount(1)
        .traceId(command.traceId())
        .createdAt(now)
        .updatedAt(now)
        .build();
    operationMapper.insert(operation);
    ReplayOperationEntity persisted =
        operationMapper.selectByIdentity(command.tenantId(), operationKey);
    boolean previewOperationWasNotLinked = EmptyChecks.isNull(persisted)
        || EmptyChecks.isNull(persisted.id())
        || tokenMapper.linkOperation(command.tenantId(), tokenHash, persisted.id()) != 1;
    if (previewOperationWasNotLinked) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.workflow_run_replay_operation_not_persisted");
    }
    return persisted;
  }

  @Transactional
  public ReplayOperationEntity reject(String tenantId, Long operationId) {
    operationMapper.setTenantContext(tenantId);
    ReplayOperationEntity operation = operationMapper.selectForUpdate(tenantId, operationId);
    if (EmptyChecks.isNull(operation)
        || !ReplayOperationType.WORKFLOW_RUN.code().equals(operation.operationType())) {
      throw BizException.of(
          ResultCode.NOT_FOUND, "error.workflow.workflow_run_replay_operation_not_found");
    }
    ApprovalCommandEntity approval = requireBoundWorkflowRunReplayApproval(operation);
    if (!ApprovalCommandStatus.REJECTED.code().equals(approval.getApprovalStatus())) {
      throw invalidApprovalDecision();
    }
    if (ReplayOperationStatus.REJECTED.code().equals(operation.status())) {
      return operation;
    }
    if (!ReplayOperationStatus.PENDING_APPROVAL.code().equals(operation.status())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.workflow_run_replay_approval_state_invalid");
    }
    Instant rejectedAt = dateTimeSupport.nowInstant();
    int rejectedCount = operationMapper.rejectPendingApproval(tenantId, operationId, rejectedAt);
    if (rejectedCount != 1) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.workflow_run_replay_approval_state_invalid");
    }
    return operationMapper.selectById(tenantId, operationId);
  }

  @Transactional
  public ReplayOperationEntity approve(String tenantId, Long operationId, String approver) {
    boolean approvalRequestIsIncomplete = EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isNull(operationId)
        || operationId <= 0
        || EmptyChecks.isBlank(approver);
    if (approvalRequestIsIncomplete) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_request");
    }
    operationMapper.setTenantContext(tenantId);
    ReplayOperationEntity operation = operationMapper.selectForUpdate(tenantId, operationId);
    if (EmptyChecks.isNull(operation)
        || !ReplayOperationType.WORKFLOW_RUN.code().equals(operation.operationType())) {
      throw BizException.of(
          ResultCode.NOT_FOUND, "error.workflow.workflow_run_replay_operation_not_found");
    }
    ApprovalCommandEntity approval = requireBoundWorkflowRunReplayApproval(operation);
    boolean approved = ApprovalCommandStatus.APPROVED.code().equals(approval.getApprovalStatus())
        || ApprovalCommandStatus.EXECUTED.code().equals(approval.getApprovalStatus());
    if (!approved || EmptyChecks.isBlank(approval.getApproverId())) {
      throw invalidApprovalDecision();
    }
    String normalizedApprover = approval.getApproverId().trim();
    String requestId = WORKFLOW_REPLAY_REQUEST_PREFIX + operation.id();
    if (!ReplayOperationStatus.PENDING_APPROVAL.code().equals(operation.status())) {
      if (normalizedApprover.equals(operation.approvedBy())
          && requestId.equals(operation.targetRequestId())) {
        return operation;
      }
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.workflow_run_replay_approval_state_invalid");
    }

    WorkflowRunReplaySnapshotResolver.Snapshot snapshot =
        snapshotResolver.resolve(operation, requestId);
    Instant approvedAt = dateTimeSupport.nowInstant();
    String dedupKey = tenantId + WORKFLOW_REPLAY_DEDUP_PREFIX + operation.id();
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(snapshot.workflowDefinitionId())
        .bizDate(snapshot.bizDate())
        .triggerType(TriggerType.RERUN)
        .requestId(requestId)
        .traceId(operation.traceId())
        .params(Map.of())
        .dataIntervalStart(snapshot.dataIntervalStart())
        .dataIntervalEnd(snapshot.dataIntervalEnd())
        .dryRun(snapshot.dryRun())
        .build();
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setTenantId(tenantId);
    triggerRequest.setRequestId(requestId);
    triggerRequest.setTriggerType(TriggerType.RERUN.code());
    triggerRequest.setWorkflowDefinitionId(snapshot.workflowDefinitionId());
    triggerRequest.setBizDate(snapshot.bizDate());
    triggerRequest.setDedupKey(dedupKey);
    triggerRequest.setRequestStatus(TriggerRequestStatus.ACCEPTED.code());
    triggerRequest.setTraceId(operation.traceId());
    triggerRequest.setDryRun(snapshot.dryRun());
    triggerRequest.setLaunchPayload(JsonUtils.toJson(launchRequest));
    triggerRequest.setCreatedAt(approvedAt);

    ReplayOperationStartParam startParam = ReplayOperationStartParam.builder()
        .tenantId(tenantId)
        .operationId(operationId)
        .targetRequestId(requestId)
        .approver(normalizedApprover)
        .now(approvedAt)
        .build();
    if (operationMapper.startApprovedWorkflowRunReplay(startParam) != 1) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.workflow_run_replay_approval_state_invalid");
    }
    if (triggerRequestMapper.insert(triggerRequest) != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.trigger_target_mismatch");
    }
    TriggerRequestEntity accepted =
        triggerRequestMapper.selectByTenantAndRequestId(tenantId, requestId);
    boolean acceptedReplayLaunchIsIncomplete = EmptyChecks.isNull(accepted)
        || EmptyChecks.isNull(accepted.getId())
        || EmptyChecks.isBlank(accepted.getLaunchPayload());
    if (acceptedReplayLaunchIsIncomplete) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.trigger_target_mismatch");
    }
    LaunchRequest frozen = JsonUtils.fromJson(accepted.getLaunchPayload(), LaunchRequest.class);
    boolean launchRequestDoesNotMatchReplayIdentity =
        !LaunchRequestIdentityGuard.matches(frozen, accepted)
            || !requestId.equals(frozen.requestId())
            || !snapshot.workflowDefinitionId().equals(frozen.workflowDefinitionId())
            || !snapshot.bizDate().equals(frozen.bizDate());
    if (launchRequestDoesNotMatchReplayIdentity) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.trigger_target_mismatch");
    }
    LaunchEnvelope envelope =
        LaunchEnvelope.of(frozen, accepted.getDedupKey(), accepted.getCreatedAt());
    DomainEvent.Builder launchEventBuilder = DomainEvent.builder(tenantId)
        .aggregate("TRIGGER_REQUEST", accepted.getId())
        .type("WORKFLOW_LAUNCH")
        .key(dedupKey + ":launch");
    Map<String, Object> launchEventPayload = JsonUtils.toMap(envelope);
    DomainEvent launchEvent =
        launchEventBuilder.payload(launchEventPayload).traceId(frozen.traceId()).build();
    domainEventPublisher.publish(launchEvent);
    return operationMapper.selectById(tenantId, operationId);
  }

  /** operationKey 已作为审批来源键唯一持久化，批准与拒绝都读取同一真实决策。 */
  private ApprovalCommandEntity requireBoundWorkflowRunReplayApproval(
      ReplayOperationEntity operation) {
    if (EmptyChecks.isBlank(operation.operationKey())) {
      throw invalidApprovalDecision();
    }
    ApprovalCommandEntity approval = approvalCommandMapper.selectByTenantAndSourceIdempotencyKey(
        operation.tenantId(), operation.operationKey());
    boolean sameTarget = EmptyChecks.isNotNull(approval)
        && operation.tenantId().equals(approval.getTenantId())
        && operation.operationKey().equals(approval.getSourceIdempotencyKey())
        && "COMPENSATION".equals(approval.getApprovalType())
        && "WORKFLOW_RUN_REPLAY".equals(approval.getActionType())
        && "WORKFLOW_RUN".equals(approval.getTargetType())
        && EmptyChecks.isNotNull(operation.sourceWorkflowRunId())
        && String.valueOf(operation.sourceWorkflowRunId()).equals(approval.getTargetId());
    if (!sameTarget) {
      throw invalidApprovalDecision();
    }
    WorkflowRunReplayApprovalPayload payload;
    try {
      payload = JsonUtils.fromJsonStrict(
          approval.getPayloadJson(), WorkflowRunReplayApprovalPayload.class);
    } catch (IllegalArgumentException invalidPayload) {
      throw invalidApprovalDecision();
    }
    if (EmptyChecks.isNull(payload)
        || !operation.tenantId().equals(payload.tenantId())
        || !operation.id().equals(payload.replayOperationId())) {
      throw invalidApprovalDecision();
    }
    return approval;
  }

  private BizException invalidApprovalDecision() {
    return BizException.of(
        ResultCode.STATE_CONFLICT, "error.workflow.workflow_run_replay_approval_state_invalid");
  }

  private record WorkflowRunReplayApprovalPayload(String tenantId, Long replayOperationId) {}

  private BizException previewChanged() {
    return BizException.of(
        ResultCode.STATE_CONFLICT, "error.workflow.workflow_run_replay_preview_changed");
  }

  private PreparedPreview preparePreview(
      WorkflowRunReplayCommand command, UUID operationKey, String rawToken, Instant expiresAt) {
    Policies policies = validate(command);
    Source source = sourceResolver.resolve(command.tenantId(), command.workflowRunId());
    if (EmptyChecks.isNull(source.run().getBizDate())
        || EmptyChecks.isBlank(source.executionSnapshot().workflowCalendarCode())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.replay_source_snapshot_invalid");
    }
    List<WorkflowNodeRunEntity> sourceNodeRuns =
        nodeRunMapper.selectByWorkflowRunId(command.workflowRunId());
    Set<String> unknownNodes = unknownOutcomeNodes(command, sourceNodeRuns);
    Map<String, WorkflowNodeRunEntity> latestNodeRunsByNode = latestNodeRuns(sourceNodeRuns);
    SelectedConfigurations selectedConfigurations = new SelectedConfigurations(
        new LinkedHashMap<>(),
        new LinkedHashMap<>(),
        new LinkedHashMap<>(),
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
    PreviewContext context = new PreviewContext(
        command,
        source,
        policies,
        unknownNodes,
        latestNodeRunsByNode,
        new LinkedHashMap<>(),
        selectedConfigurations);
    List<NodePreview> nodes = source.executionSnapshot().nodes().stream()
        .map(node -> previewNode(node, context))
        .toList();
    List<EdgePreview> edges = source.executionSnapshot().edges().stream()
        .map(edge -> new EdgePreview(
            edge.getFromNodeCode(), edge.getToNodeCode(), edge.getEdgeType(), edge.getEnabled()))
        .toList();
    WorkflowRunReplayPreviewResponse response = WorkflowRunReplayPreviewResponse.builder()
        .tenantId(command.tenantId())
        .sourceWorkflowRunId(source.run().getId())
        .workflowDefinitionId(source.executionSnapshot().workflowDefinitionId())
        .workflowVersion(source.executionSnapshot().workflowVersion())
        .bizDate(source.run().getBizDate())
        .calendarCode(source.executionSnapshot().workflowCalendarCode())
        .dataIntervalStart(source.run().getDataIntervalStart())
        .dataIntervalEnd(source.run().getDataIntervalEnd())
        .resultPolicy(policies.result().code())
        .configVersionPolicy(policies.config().code())
        .configVersion(command.configVersion())
        .operationKey(operationKey)
        .previewToken(rawToken)
        .expiresAt(expiresAt)
        .nodes(nodes)
        .edges(edges)
        .build();
    String payload = snapshotPayload(source, response, selectedConfigurations);
    return new PreparedPreview(response, source, policies, payload);
  }

  private String snapshotPayload(
      Source source, WorkflowRunReplayPreviewResponse response, SelectedConfigurations selected) {
    Map<String, Object> payload = new LinkedHashMap<>();
    Map<String, Object> previewSnapshot = new LinkedHashMap<>();
    previewSnapshot.put("tenantId", response.tenantId());
    previewSnapshot.put("sourceWorkflowRunId", response.sourceWorkflowRunId());
    previewSnapshot.put("workflowDefinitionId", response.workflowDefinitionId());
    previewSnapshot.put("workflowVersion", response.workflowVersion());
    previewSnapshot.put("bizDate", response.bizDate());
    previewSnapshot.put("calendarCode", response.calendarCode());
    previewSnapshot.put("dataIntervalStart", response.dataIntervalStart());
    previewSnapshot.put("dataIntervalEnd", response.dataIntervalEnd());
    previewSnapshot.put("resultPolicy", response.resultPolicy());
    previewSnapshot.put("configVersionPolicy", response.configVersionPolicy());
    previewSnapshot.put("configVersion", response.configVersion());
    previewSnapshot.put("nodes", response.nodes());
    previewSnapshot.put("edges", response.edges());
    payload.put("preview", previewSnapshot);
    boolean sourceRunIsDryRun = Boolean.TRUE.equals(source.run().getDryRun());
    payload.put("dryRun", sourceRunIsDryRun);
    payload.put("singleJobDefinitionId", source.run().getSingleJobDefinitionId());
    payload.put("sourceExecutionSnapshot", source.run().getExecutionSnapshot());
    payload.put("sourceProtectedRootParams", source.protectedRootParams());
    payload.put("selectedJobDefinitions", selected.selectedDefinitions());
    payload.put("selectedFileTemplateSnapshots", selected.selectedFileTemplateSnapshots());
    payload.put("selectedDispatchChannelSnapshots", selected.selectedDispatchChannelSnapshots());
    payload.put("selectedDispatchSecretBindings", selected.selectedDispatchSecretBindings());
    payload.put(
        "selectedCrossDayDependencyResultVersionIdsByNode",
        selected.selectedCrossDayDependencyResultVersionIds());
    Map<String, Object> selectedReplayCapabilities = new LinkedHashMap<>();
    for (NodePreview node : response.nodes()) {
      WorkflowReplayCapabilityService.Assessment assessment = node.capability();
      boolean capabilityDeclarationIsPersistable = EmptyChecks.isNotNull(assessment)
          && assessment.status() == WorkflowReplayCapabilityService.Status.DECLARED_CONSISTENT
          && EmptyChecks.isNotNull(assessment.declaration());
      if (capabilityDeclarationIsPersistable) {
        selectedReplayCapabilities.put(node.nodeCode(), assessment.declaration());
      }
    }
    payload.put("selectedReplayCapabilities", selectedReplayCapabilities);
    return JsonUtils.toJson(payload);
  }

  private String requestHash(WorkflowRunReplayCommand command, Policies policies) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("tenantId", command.tenantId());
    request.put("workflowRunId", command.workflowRunId());
    String normalizedReason = command.reason().trim();
    request.put("reason", normalizedReason);
    String normalizedRequestedBy = command.requestedBy().trim();
    request.put("requestedBy", normalizedRequestedBy);
    request.put("resultPolicy", policies.result().code());
    request.put("configVersionPolicy", policies.config().code());
    request.put("configVersion", command.configVersion());
    String requestJson = JsonUtils.toJson(request);
    return sha256(requestJson);
  }

  private String sha256(String value) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
      byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
      byte[] digest = messageDigest.digest(valueBytes);
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private NodePreview previewNode(WorkflowNodeEntity node, PreviewContext context) {
    List<String> blockers = new ArrayList<>();
    if (context.unknownNodes().contains(node.getNodeCode())) {
      blockers.add(Blocker.EXTERNAL_OUTCOME_UNKNOWN.name());
    }
    if (!WorkflowNodeType.JOB.code().equals(node.getNodeType())) {
      return nodePreview(node, null, null, null, blockers);
    }
    boolean shouldResolveCrossDayDependencies = !Boolean.FALSE.equals(node.getEnabled())
        && EmptyChecks.isNotBlank(node.getCrossDayDependencies());
    if (shouldResolveCrossDayDependencies) {
      boolean dependencyListIsEmptyArray =
          node.getCrossDayDependencies().trim().matches("\\[\\s*\\]");
      shouldResolveCrossDayDependencies = !dependencyListIsEmptyArray;
    }
    if (shouldResolveCrossDayDependencies) {
      CrossDayDependencyResolver.ResolutionResult dependencyResults =
          crossDayDependencyResolver.resolve(
              context.command().tenantId(),
              context.source().run().getBizDate(),
              node.getCrossDayDependencies(),
              Boolean.TRUE.equals(context.source().run().getDryRun()));
      if (dependencyResults.isResolved()
          && EmptyChecks.isNotEmpty(dependencyResults.getResultVersionIdsByAlias())) {
        context
            .selectedConfigurations()
            .selectedCrossDayDependencyResultVersionIds()
            .put(node.getNodeCode(), dependencyResults.getResultVersionIdsByAlias());
      } else {
        blockers.add(Blocker.DEPENDENCY_RESULTS_NOT_PINNED.name());
      }
    }
    JobDefinitionView definition;
    try {
      definition = resolveDefinition(node, context);
    } catch (BizException unavailableConfiguration) {
      blockers.add(Blocker.CONFIGURATION_UNAVAILABLE.name());
      return nodePreview(node, null, null, null, blockers);
    }
    context.selectedConfigurations().selectedDefinitions().put(node.getNodeCode(), definition);
    if (Boolean.FALSE.equals(node.getEnabled())) {
      return nodePreview(node, definition, null, null, blockers);
    }
    String pluginId = selectedPlugin(node, definition, context, blockers);
    if (EmptyChecks.isBlank(pluginId)) {
      blockers.add(Blocker.PLUGIN_SELECTION_NOT_FROZEN.name());
    }
    WorkflowReplayCapabilityService.Assessment capability =
        capabilityService.assess(context.command().tenantId(), definition.workerGroup(), pluginId);
    if (capability.status() != WorkflowReplayCapabilityService.Status.DECLARED_CONSISTENT) {
      blockers.add(capability.status().name());
    }
    return nodePreview(node, definition, pluginId, capability, blockers);
  }

  private JobDefinitionView resolveDefinition(WorkflowNodeEntity node, PreviewContext context) {
    JobDefinitionView latest = null;
    if (context.policies().config() == ConfigVersionPolicy.USE_LATEST_CONFIG) {
      String jobCode = node.getRelatedJobCode();
      if (!context.latestDefinitions().containsKey(jobCode)) {
        JobDefinitionView selected =
            configCacheService.findEnabledJobDefinition(context.command().tenantId(), jobCode);
        context.latestDefinitions().put(jobCode, selected);
      }
      latest = context.latestDefinitions().get(jobCode);
    }
    WorkflowReplayJobDefinitionRequest request = WorkflowReplayJobDefinitionRequest.builder()
        .tenantId(context.command().tenantId())
        .nodeCode(node.getNodeCode())
        .jobCode(node.getRelatedJobCode())
        .sourceSnapshot(context.source().executionSnapshot())
        .latestDefinition(latest)
        .policy(context.policies().config())
        .specifiedVersion(context.command().configVersion())
        .build();
    return definitionResolver.resolve(request);
  }

  private String selectedPlugin(
      WorkflowNodeEntity node,
      JobDefinitionView definition,
      PreviewContext context,
      List<String> blockers) {
    Map<String, Object> params =
        effectiveJobParams(node, definition, context.source().rootParams());
    if (EmptyChecks.isNull(params)) {
      return null;
    }
    JobType jobType = DictEnum.fromCode(JobType.class, definition.jobType());
    if (isFileTemplateJob(jobType)) {
      Map<String, Object> originalParams =
          context.policies().config() == ConfigVersionPolicy.USE_ORIGINAL_CONFIG
              ? originalEffectiveParams(node, definition, context)
              : null;
      try {
        FileTemplateSnapshotService.ReplayTemplateSelection selection =
            fileTemplateSnapshotService.captureForWorkflowReplay(
                context.command().tenantId(),
                definition,
                params,
                context.policies().config(),
                originalParams);
        boolean templateSnapshotIsUnavailable = EmptyChecks.isNull(selection)
            || EmptyChecks.isEmpty(Objects.requireNonNull(selection.protectedSnapshots()));
        if (templateSnapshotIsUnavailable) {
          blockers.add(Blocker.TEMPLATE_CONFIGURATION_NOT_FROZEN.name());
          return null;
        }
        context
            .selectedConfigurations()
            .selectedFileTemplateSnapshots()
            .put(node.getNodeCode(), selection.protectedSnapshots());
        return selection.pluginId();
      } catch (BizException unavailableTemplate) {
        blockers.add(Blocker.TEMPLATE_CONFIGURATION_NOT_FROZEN.name());
        return null;
      }
    }
    if (jobType == JobType.DISPATCH) {
      return selectedDispatchPlugin(node, definition, params, context, blockers);
    }

    return resolveNonFilePlugin(jobType, params);
  }

  private String selectedDispatchPlugin(
      WorkflowNodeEntity node,
      JobDefinitionView definition,
      Map<String, Object> effectiveParams,
      PreviewContext context,
      List<String> blockers) {
    Map<String, Object> originalParams =
        context.policies().config() == ConfigVersionPolicy.USE_ORIGINAL_CONFIG
            ? originalEffectiveParams(node, definition, context)
            : null;
    try {
      Map<String, Object> selectedParams;
      if (context.policies().config() == ConfigVersionPolicy.USE_ORIGINAL_CONFIG) {
        selectedParams = EmptyChecks.isNull(originalParams) ? Map.of() : originalParams;
      } else {
        selectedParams = dispatchSecretSnapshotResolver.pin(
            context.command().tenantId(), definition.jobType(), effectiveParams);
      }
      Map<String, Object> selectedSecretBindings = dispatchSecretBindings(selectedParams);
      DispatchChannelSnapshotService.ReplayDispatchCaptureRequest captureRequest =
          new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
              context.command().tenantId(),
              definition.jobType(),
              effectiveParams,
              context.policies().config(),
              originalParams,
              selectedSecretBindings);
      DispatchChannelSnapshotService.ReplayDispatchSelection selection =
          dispatchChannelSnapshotService.captureForWorkflowReplay(captureRequest);
      boolean dispatchSelectionIsIncomplete = EmptyChecks.isNull(selection)
          || EmptyChecks.isEmpty(Objects.requireNonNull(selection.protectedSnapshots()))
          || EmptyChecks.isBlank(selection.pluginId());
      if (dispatchSelectionIsIncomplete) {
        blockers.add(Blocker.CONFIGURATION_UNAVAILABLE.name());
        return null;
      }
      context
          .selectedConfigurations()
          .selectedDispatchChannelSnapshots()
          .put(node.getNodeCode(), selection.protectedSnapshots());
      if (!EmptyChecks.isEmpty(Objects.requireNonNull(selection.secretBindings()))) {
        context
            .selectedConfigurations()
            .selectedDispatchSecretBindings()
            .put(node.getNodeCode(), selection.secretBindings());
      }
      return selection.pluginId();
    } catch (BizException unavailableConfiguration) {
      blockers.add(Blocker.CONFIGURATION_UNAVAILABLE.name());
      return null;
    }
  }

  private Map<String, Object> dispatchSecretBindings(Map<String, Object> params) {
    Object rawBindings = params.get(DispatchTaskKeys.SECRET_BINDINGS);
    if (EmptyChecks.isNull(rawBindings)) {
      return Map.of();
    }
    if (!(rawBindings instanceof Map<?, ?> bindings)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.replay_source_snapshot_invalid");
    }
    Map<String, Object> selectedBindings = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : bindings.entrySet()) {
      if (!(entry.getKey() instanceof String channelCode) || EmptyChecks.isBlank(channelCode)) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT, "error.workflow.replay_source_snapshot_invalid");
      }
      selectedBindings.put(channelCode, entry.getValue());
    }
    return selectedBindings;
  }

  private Map<String, Object> effectiveJobParams(
      WorkflowNodeEntity node, JobDefinitionView definition, Map<String, Object> rootParams) {
    Map<String, Object> params = new LinkedHashMap<>();
    if (EmptyChecks.isNotNull(definition.defaultParams())) {
      params.putAll(definition.defaultParams());
    }
    if (EmptyChecks.isNotNull(rootParams)) {
      params.putAll(rootParams);
    }
    if (EmptyChecks.isNotBlank(node.getNodeParams())) {
      try {
        String serializedNodeParams = node.getNodeParams();
        Map<String, Object> nodeParams =
            JsonUtils.fromJson(serializedNodeParams, new TypeReference<Map<String, Object>>() {});
        if (EmptyChecks.isNotNull(nodeParams)) {
          nodeParams.forEach(params::putIfAbsent);
        }
      } catch (IllegalArgumentException invalidNodeParameters) {
        return null;
      }
    }
    return params;
  }

  private Map<String, Object> originalEffectiveParams(
      WorkflowNodeEntity node, JobDefinitionView definition, PreviewContext context) {
    WorkflowNodeRunEntity sourceNode = context.sourceNodeRuns().get(node.getNodeCode());
    boolean sourceNodeDoesNotBelongToReplayRun = EmptyChecks.isNull(sourceNode)
        || !context.source().run().getId().equals(sourceNode.getWorkflowRunId())
        || EmptyChecks.isNull(sourceNode.getJobInstanceId());
    if (sourceNodeDoesNotBelongToReplayRun) {
      return null;
    }
    JobInstanceEntity sourceInstance =
        jobInstanceMapper.selectById(context.command().tenantId(), sourceNode.getJobInstanceId());
    JobDefinitionView sourceDefinition =
        context.source().executionSnapshot().jobDefinition(node.getNodeCode());
    boolean sourceInstanceIsNotEligibleForReplay = EmptyChecks.isNull(sourceInstance)
        || !context.command().tenantId().equals(sourceInstance.getTenantId())
        || !definition.jobCode().equals(sourceInstance.getJobCode())
        || !context.source().run().getBizDate().equals(sourceInstance.getBizDate())
        || EmptyChecks.isNull(sourceDefinition)
        || !Objects.equals(sourceDefinition.id(), sourceInstance.getJobDefinitionId())
        || !Objects.equals(sourceDefinition.version(), sourceInstance.getJobDefinitionVersion())
        || EmptyChecks.isBlank(sourceInstance.getParamsSnapshot());
    if (sourceInstanceIsNotEligibleForReplay) {
      return null;
    }
    try {
      String serializedParamsSnapshot = sourceInstance.getParamsSnapshot();
      Map<String, Object> paramsSnapshot =
          JsonUtils.fromJson(serializedParamsSnapshot, new TypeReference<Map<String, Object>>() {});
      Object effectiveParams = paramsSnapshot.get("effectiveParams");
      if (!(effectiveParams instanceof Map<?, ?> values)) {
        return null;
      }
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : values.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          return null;
        }
        result.put(key, entry.getValue());
      }
      return result;
    } catch (RuntimeException invalidSourceParams) {
      return null;
    }
  }

  private String resolveNonFilePlugin(JobType type, Map<String, Object> params) {
    String jobType = EmptyChecks.isNull(type) ? null : type.code();
    Object plugin = null;
    if (JobType.PROCESS == type) {
      plugin = params.get("processImplCode");
    } else if (JobType.ATOMIC == type || JobType.GENERAL == type) {
      Object subtype = params.get("taskType");
      // Match DefaultStepExecutionAdapter: explicit nonblank string, then task_type.
      plugin = subtype instanceof String text && EmptyChecks.isNotBlank(text) ? text : jobType;
    }
    if (!(plugin instanceof String id)
        || EmptyChecks.isBlank(id)
        || id.contains("$.")
        || id.contains("${")) {
      return null;
    }
    return id.trim();
  }

  private boolean isFileTemplateJob(JobType type) {
    return type == JobType.IMPORT
        || type == JobType.EXPORT
        || type == JobType.BUNDLE_IMPORT
        || type == JobType.BUNDLE_EXPORT;
  }

  private Map<String, WorkflowNodeRunEntity> latestNodeRuns(
      List<WorkflowNodeRunEntity> sourceNodes) {
    Map<String, WorkflowNodeRunEntity> latestByNode = new LinkedHashMap<>();
    if (EmptyChecks.isEmpty(sourceNodes)) {
      return latestByNode;
    }
    for (WorkflowNodeRunEntity candidate : sourceNodes) {
      if (EmptyChecks.isNull(candidate) || EmptyChecks.isBlank(candidate.getNodeCode())) {
        continue;
      }
      WorkflowNodeRunEntity existing = latestByNode.get(candidate.getNodeCode());
      boolean candidateIsLatestForNode = EmptyChecks.isNull(existing)
          || runSequence(candidate) > runSequence(existing)
          || (Objects.equals(candidate.getRunSeq(), existing.getRunSeq())
              && EmptyChecks.isNull(existing.getJobInstanceId())
              && EmptyChecks.isNotNull(candidate.getJobInstanceId()));
      if (candidateIsLatestForNode) {
        latestByNode.put(candidate.getNodeCode(), candidate);
      }
    }
    return latestByNode;
  }

  private int runSequence(WorkflowNodeRunEntity nodeRun) {
    return EmptyChecks.isNull(nodeRun.getRunSeq()) ? 0 : nodeRun.getRunSeq();
  }

  private Set<String> unknownOutcomeNodes(
      WorkflowRunReplayCommand command, List<WorkflowNodeRunEntity> sourceNodes) {
    Set<String> unknown = new HashSet<>();
    if (EmptyChecks.isNull(sourceNodes)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.replay_source_snapshot_invalid");
    }
    for (WorkflowNodeRunEntity node : sourceNodes) {
      if (EmptyChecks.isNotNull(node.getJobInstanceId())
          && jobTaskMapper.existsUnknownExternalOutcomeForJobInstance(
              command.tenantId(), node.getJobInstanceId())) {
        unknown.add(node.getNodeCode());
      }
    }
    return unknown;
  }

  private NodePreview nodePreview(
      WorkflowNodeEntity node,
      JobDefinitionView definition,
      String pluginId,
      WorkflowReplayCapabilityService.Assessment capability,
      List<String> blockers) {
    String nodeCode = node.getNodeCode();
    String nodeType = node.getNodeType();
    Boolean enabled = node.getEnabled();
    String jobCode = node.getRelatedJobCode();
    boolean definitionMissing = EmptyChecks.isNull(definition);
    Long definitionId = definitionMissing ? null : definition.id();
    Integer definitionVersion = definitionMissing ? null : definition.version();
    String workerGroup = definitionMissing ? null : definition.workerGroup();
    return new NodePreview(
        nodeCode,
        nodeType,
        enabled,
        jobCode,
        definitionId,
        definitionVersion,
        workerGroup,
        pluginId,
        capability,
        List.copyOf(blockers));
  }

  private Policies validate(WorkflowRunReplayCommand command) {
    boolean replaySubmitCommandIsIncomplete = EmptyChecks.isNull(command)
        || EmptyChecks.isBlank(command.tenantId())
        || EmptyChecks.isNull(command.workflowRunId())
        || command.workflowRunId() <= 0
        || EmptyChecks.isBlank(command.reason())
        || EmptyChecks.isBlank(command.requestedBy());
    if (replaySubmitCommandIsIncomplete) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_request");
    }
    ConfigVersionPolicy config = EmptyChecks.isBlank(command.configVersionPolicy())
        ? ConfigVersionPolicy.USE_ORIGINAL_CONFIG
        : ConfigVersionPolicy.fromCodeOrNull(command.configVersionPolicy());
    ResultVersionPolicy result = EmptyChecks.isBlank(command.resultPolicy())
        ? ResultVersionPolicy.CREATE_NEW_VERSION
        : ResultVersionPolicy.fromCodeOrNull(command.resultPolicy());
    boolean replayPolicyPairSupported = EmptyChecks.isNotNull(config)
        && EmptyChecks.isNotNull(result)
        && result != ResultVersionPolicy.DRY_RUN_ONLY;
    boolean replayPolicyIsValid = replayPolicyPairSupported
        && (config == ConfigVersionPolicy.USE_SPECIFIED_VERSION
            ? EmptyChecks.isNotNull(command.configVersion()) && command.configVersion() > 0
            : EmptyChecks.isNull(command.configVersion()));
    if (!replayPolicyIsValid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.workflow.replay_invalid_policy");
    }
    return new Policies(config, result);
  }

  private enum Blocker {
    EXTERNAL_OUTCOME_UNKNOWN,
    DEPENDENCY_RESULTS_NOT_PINNED,
    CONFIGURATION_UNAVAILABLE,
    TEMPLATE_CONFIGURATION_NOT_FROZEN,
    PLUGIN_SELECTION_NOT_FROZEN
  }

  private record Policies(ConfigVersionPolicy config, ResultVersionPolicy result) {}

  private record PreparedPreview(
      WorkflowRunReplayPreviewResponse response,
      Source source,
      Policies policies,
      String snapshotPayload) {}

  /** 预览实际选择的执行配置；序列化时整体封存，避免审批后重新选择。 */
  private record SelectedConfigurations(
      Map<String, JobDefinitionView> selectedDefinitions,
      Map<String, Map<String, String>> selectedFileTemplateSnapshots,
      Map<String, Map<String, String>> selectedDispatchChannelSnapshots,
      Map<String, Map<String, Object>> selectedDispatchSecretBindings,
      Map<String, Map<String, List<Long>>> selectedCrossDayDependencyResultVersionIds) {

    private SelectedConfigurations {
      Objects.requireNonNull(selectedDefinitions, "selectedDefinitions");
      Objects.requireNonNull(selectedFileTemplateSnapshots, "selectedFileTemplateSnapshots");
      Objects.requireNonNull(selectedDispatchChannelSnapshots, "selectedDispatchChannelSnapshots");
      Objects.requireNonNull(selectedDispatchSecretBindings, "selectedDispatchSecretBindings");
      Objects.requireNonNull(
          selectedCrossDayDependencyResultVersionIds, "selectedCrossDayDependencyResultVersionIds");
    }

    @Override
    public String toString() {
      return "SelectedConfigurations[<redacted>]";
    }
  }

  private record PreviewContext(
      WorkflowRunReplayCommand command,
      Source source,
      Policies policies,
      Set<String> unknownNodes,
      Map<String, WorkflowNodeRunEntity> sourceNodeRuns,
      Map<String, JobDefinitionView> latestDefinitions,
      SelectedConfigurations selectedConfigurations) {}
}
