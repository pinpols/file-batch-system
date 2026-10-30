package io.github.pinpols.batch.orchestrator.application.service.replay;

import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.ReplayOperationStatus;
import io.github.pinpols.batch.common.enums.ReplayOperationType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.ResultVersionPolicy;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.governance.WorkflowNodeReplayTargetResolver;
import io.github.pinpols.batch.orchestrator.application.service.governance.WorkflowNodeReplayTargetResolver.WorkflowNodeReplayTarget;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationPreviewTokenEntity;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationPreviewTokenMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADR-049 针对精确失败 JOB 节点的预览与审批准入。 */
@Service
public class WorkflowNodeReplayOperationService {

  private static final Duration PREVIEW_TTL = Duration.ofMinutes(5);
  private static final String UNKNOWN_EXTERNAL_OUTCOME = "EXTERNAL_OUTCOME_UNKNOWN";
  private static final String SIDE_EFFECT_WARNING =
      "Only the selected failed JOB node is replayed; its external side effects may run again.";

  private final WorkflowNodeReplayTargetResolver targetResolver;
  private final ReplayOperationMapper operationMapper;
  private final ReplayOperationPreviewTokenMapper tokenMapper;
  private final BatchDateTimeSupport dateTimeSupport;

  public WorkflowNodeReplayOperationService(
      WorkflowNodeReplayTargetResolver targetResolver,
      ReplayOperationMapper operationMapper,
      ReplayOperationPreviewTokenMapper tokenMapper,
      BatchDateTimeSupport dateTimeSupport) {
    this.targetResolver = targetResolver;
    this.operationMapper = operationMapper;
    this.tokenMapper = tokenMapper;
    this.dateTimeSupport = dateTimeSupport;
  }

  @Transactional
  public WorkflowNodeReplayPreviewResponse preview(WorkflowNodeReplayCommand command) {
    command = withDefaultPolicies(command);
    validate(command, false);
    operationMapper.setTenantContext(command.tenantId());
    tokenMapper.setTenantContext(command.tenantId());
    WorkflowNodeReplayTarget source = resolveSafeSource(command);
    CompensationSubmitCommand replayCommand = replayCommand(command, source);
    String snapshotPayload = snapshotPayload(source, replayCommand);
    String requestHash = requestHash(replayCommand);
    String snapshotHash = sha256(snapshotPayload);
    UUID operationKey = UUID.randomUUID();
    UUID rawTokenId = UUID.randomUUID();
    String rawToken = rawTokenId.toString();
    Instant now = dateTimeSupport.nowInstant();
    Instant expiresAt = now.plus(PREVIEW_TTL);
    ReplayOperationPreviewTokenEntity.ReplayOperationPreviewTokenEntityBuilder previewTokenBuilder =
        ReplayOperationPreviewTokenEntity.builder().tenantId(command.tenantId());
    String tokenHash = sha256(rawToken);
    previewTokenBuilder
        .tokenHash(tokenHash)
        .operationKey(operationKey.toString())
        .requestHash(requestHash)
        .snapshotHash(snapshotHash)
        .snapshotPayload(snapshotPayload)
        .expiresAt(expiresAt)
        .createdAt(now);
    tokenMapper.insert(previewTokenBuilder.build());
    JobInstanceEntity sourceInstance = source.sourceJobInstance();
    String tenantId = command.tenantId();
    Long sourceRunId = source.sourceRun().getId();
    Long sourceNodeRunId = source.sourceNodeRun().getId();
    String sourceNodeCode = source.sourceNodeRun().getNodeCode();
    Integer sourceRunSeq = source.sourceNodeRun().getRunSeq();
    Long sourceInstanceId = sourceInstance.getId();
    String sourceJobCode = sourceInstance.getJobCode();
    LocalDate businessDate = source.sourceRun().getBizDate();
    Integer sourceJobDefinitionVersion = sourceInstance.getJobDefinitionVersion();
    String resultPolicy = replayCommand.resultPolicy();
    String configVersionPolicy = replayCommand.configVersionPolicy();
    Integer configVersion = replayCommand.configVersion();
    List<String> warnings = List.of(SIDE_EFFECT_WARNING);
    return WorkflowNodeReplayPreviewResponse.builder()
        .tenantId(tenantId)
        .operationKey(operationKey)
        .sourceWorkflowRunId(sourceRunId)
        .sourceWorkflowNodeRunId(sourceNodeRunId)
        .sourceNodeCode(sourceNodeCode)
        .sourceNodeRunSeq(sourceRunSeq)
        .sourceJobInstanceId(sourceInstanceId)
        .jobCode(sourceJobCode)
        .bizDate(businessDate)
        .sourceConfigVersion(sourceJobDefinitionVersion)
        .resultPolicy(resultPolicy)
        .configVersionPolicy(configVersionPolicy)
        .configVersion(configVersion)
        .previewToken(rawToken)
        .expiresAt(expiresAt)
        .warnings(warnings)
        .build();
  }

  /** 按精确预览快照先创建持久父操作，再由 Console 提交审批。 */
  @Transactional
  public ReplayOperationEntity submitForApproval(WorkflowNodeReplayCommand command) {
    command = withDefaultPolicies(command);
    validate(command, true);
    operationMapper.setTenantContext(command.tenantId());
    tokenMapper.setTenantContext(command.tenantId());
    WorkflowNodeReplayTarget source = resolveSafeSource(command);
    CompensationSubmitCommand replayCommand = replayCommand(command, source);
    String requestHash = requestHash(replayCommand);
    ReplayOperationEntity existing = operationMapper.selectByIdentity(
        command.tenantId(), command.operationKey().toString());
    if (EmptyChecks.isNotNull(existing)) {
      boolean existingOperationMatchesPendingRequest = requestHash.equals(existing.requestHash())
          && ReplayOperationStatus.PENDING_APPROVAL.code().equals(existing.status());
      if (existingOperationMatchesPendingRequest) {
        return existing;
      }
      throw invalidPreview();
    }

    String tokenHash = sha256(command.previewToken());
    ReplayOperationPreviewTokenEntity token;
    try {
      token = tokenMapper.selectForUpdate(command.tenantId(), tokenHash);
    } catch (PessimisticLockingFailureException concurrentConsumption) {
      throw invalidPreview();
    }
    boolean previewTokenDoesNotMatchSubmittedRequest = EmptyChecks.isNull(token)
        || !command.operationKey().toString().equals(token.operationKey())
        || !requestHash.equals(token.requestHash());
    if (previewTokenDoesNotMatchSubmittedRequest) {
      throw invalidPreview();
    }
    if (EmptyChecks.isNotNull(token.consumedAt())) {
      ReplayOperationEntity consumedOperation = EmptyChecks.isNull(token.operationId())
          ? null
          : operationMapper.selectById(command.tenantId(), token.operationId());
      boolean consumedOperationMatchesPendingRequest = EmptyChecks.isNotNull(consumedOperation)
          && requestHash.equals(consumedOperation.requestHash())
          && ReplayOperationStatus.PENDING_APPROVAL.code().equals(consumedOperation.status());
      if (consumedOperationMatchesPendingRequest) {
        return consumedOperation;
      }
      throw invalidPreview();
    }

    Instant now = dateTimeSupport.nowInstant();
    if (!token.expiresAt().isAfter(now)) {
      throw invalidPreview();
    }
    String snapshotPayload = snapshotPayload(source, replayCommand);
    String snapshotHash = sha256(snapshotPayload);
    if (!snapshotHash.equals(token.snapshotHash())) {
      throw invalidPreview();
    }
    if (tokenMapper.consume(command.tenantId(), tokenHash, now) != 1) {
      throw invalidPreview();
    }

    ReplayOperationEntity.ReplayOperationEntityBuilder operationBuilder =
        ReplayOperationEntity.builder()
            .tenantId(command.tenantId())
            .operationKey(command.operationKey().toString())
            .operationType(ReplayOperationType.WORKFLOW_NODE.code())
            .status(ReplayOperationStatus.PENDING_APPROVAL.code())
            .scope("FAILED_NODE")
            .executionMode("REPLAY")
            .candidateSource("EXACT_SOURCE_NODE");
    Map<String, Long> sourceIdentity = Map.of(
        "sourceWorkflowRunId", source.sourceRun().getId(),
        "sourceWorkflowNodeRunId", source.sourceNodeRun().getId(),
        "sourceJobInstanceId", source.sourceJobInstance().getId());
    String scopePayload = JsonUtils.toJson(sourceIdentity);
    operationBuilder
        .scopePayload(scopePayload)
        .resultPolicy(replayCommand.resultPolicy())
        .configVersionPolicy(replayCommand.configVersionPolicy())
        .configVersion(replayCommand.configVersion())
        .sourceWorkflowRunId(source.sourceRun().getId())
        .sourceWorkflowNodeRunId(source.sourceNodeRun().getId())
        .sourceJobInstanceId(source.sourceJobInstance().getId())
        .requestHash(requestHash)
        .snapshotHash(snapshotHash)
        .snapshotPayload(snapshotPayload);
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
    ReplayOperationEntity persisted = operationMapper.selectByIdentity(
        command.tenantId(), command.operationKey().toString());
    boolean operationWasNotPersistedOrTokenLinkFailed = EmptyChecks.isNull(persisted)
        || EmptyChecks.isNull(persisted.id())
        || tokenMapper.linkOperation(command.tenantId(), tokenHash, persisted.id()) != 1;
    if (operationWasNotPersistedOrTokenLinkFailed) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.replay_operation_not_persisted");
    }
    return persisted;
  }

  private WorkflowNodeReplayTarget resolveSafeSource(WorkflowNodeReplayCommand command) {
    WorkflowNodeReplayTarget source = targetResolver.resolve(
        command.tenantId(), command.workflowRunId(), command.workflowNodeRunId());
    if (UNKNOWN_EXTERNAL_OUTCOME.equals(source.sourceNodeRun().getErrorCode())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.replay_external_outcome_unknown");
    }
    return source;
  }

  private void validate(WorkflowNodeReplayCommand command, boolean submission) {
    boolean requiredReplayIdentityIsInvalid = EmptyChecks.isNull(command)
        || !Texts.hasText(command.tenantId())
        || EmptyChecks.isNull(command.workflowRunId())
        || EmptyChecks.isNull(command.workflowNodeRunId())
        || !Texts.hasText(command.reason())
        || !Texts.hasText(command.requestedBy());
    if (requiredReplayIdentityIsInvalid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_request");
    }
    boolean submissionFieldsAreInvalid = submission
        && (EmptyChecks.isNull(command.operationKey()) || !Texts.hasText(command.previewToken()));
    if (submissionFieldsAreInvalid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_request");
    }
    ResultVersionPolicy resultPolicy = ResultVersionPolicy.fromCodeOrNull(command.resultPolicy());
    ConfigVersionPolicy configPolicy =
        ConfigVersionPolicy.fromCodeOrNull(command.configVersionPolicy());
    boolean replayPolicyPairSupported = EmptyChecks.isNotNull(resultPolicy)
        && resultPolicy != ResultVersionPolicy.DRY_RUN_ONLY
        && EmptyChecks.isNotNull(configPolicy);
    boolean replayPolicyIsValid = replayPolicyPairSupported
        && (configPolicy == ConfigVersionPolicy.USE_SPECIFIED_VERSION
            ? EmptyChecks.isNotNull(command.configVersion())
            : EmptyChecks.isNull(command.configVersion()));
    if (!replayPolicyIsValid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.workflow.replay_invalid_policy");
    }
  }

  private WorkflowNodeReplayCommand withDefaultPolicies(WorkflowNodeReplayCommand command) {
    if (EmptyChecks.isNull(command)) {
      return null;
    }
    String resultPolicy = Texts.hasText(command.resultPolicy())
        ? command.resultPolicy()
        : ResultVersionPolicy.CREATE_NEW_VERSION.code();
    String configVersionPolicy = Texts.hasText(command.configVersionPolicy())
        ? command.configVersionPolicy()
        : ConfigVersionPolicy.USE_ORIGINAL_CONFIG.code();
    return WorkflowNodeReplayCommand.builder()
        .tenantId(command.tenantId())
        .workflowRunId(command.workflowRunId())
        .workflowNodeRunId(command.workflowNodeRunId())
        .reason(command.reason())
        .requestedBy(command.requestedBy())
        .resultPolicy(resultPolicy)
        .configVersionPolicy(configVersionPolicy)
        .configVersion(command.configVersion())
        .traceId(command.traceId())
        .operationKey(command.operationKey())
        .previewToken(command.previewToken())
        .build();
  }

  private CompensationSubmitCommand replayCommand(
      WorkflowNodeReplayCommand command, WorkflowNodeReplayTarget source) {
    CompensationSubmitCommand.CompensationSubmitCommandBuilder builder =
        CompensationSubmitCommand.builder()
            .tenantId(command.tenantId())
            .compensationType(ReplayOperationType.WORKFLOW_NODE.code())
            .targetId(command.workflowNodeRunId())
            .jobCode(source.sourceJobInstance().getJobCode())
            .bizDate(source.sourceRun().getBizDate());
    String normalizedReason = command.reason().trim();
    builder.reason(normalizedReason);
    String normalizedOperatorId = command.requestedBy().trim();
    builder.operatorId(normalizedOperatorId);
    return builder
        .traceId(command.traceId())
        .resultPolicy(command.resultPolicy())
        .configVersionPolicy(command.configVersionPolicy())
        .configVersion(command.configVersion())
        .sourceWorkflowRunId(command.workflowRunId())
        .sourceWorkflowNodeRunId(command.workflowNodeRunId())
        .build();
  }

  private String snapshotPayload(
      WorkflowNodeReplayTarget source, CompensationSubmitCommand command) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schemaVersion", 1);
    snapshot.put("sourceWorkflowRunId", source.sourceRun().getId());
    snapshot.put("sourceWorkflowNodeRunId", source.sourceNodeRun().getId());
    snapshot.put("sourceNodeCode", source.sourceNodeRun().getNodeCode());
    snapshot.put("sourceNodeRunSeq", source.sourceNodeRun().getRunSeq());
    snapshot.put("sourceJobInstanceId", source.sourceJobInstance().getId());
    snapshot.put("sourceBizDate", source.sourceJobInstance().getBizDate());
    snapshot.put("sourceExecutionSnapshot", source.sourceRun().getExecutionSnapshot());
    snapshot.put("sourceJobParamsSnapshot", source.sourceJobInstance().getParamsSnapshot());
    snapshot.put("resultPolicy", command.resultPolicy());
    snapshot.put("configVersionPolicy", command.configVersionPolicy());
    snapshot.put("configVersion", command.configVersion());
    return JsonUtils.toJson(snapshot);
  }

  private String requestHash(CompensationSubmitCommand command) {
    Map<String, Object> payload = JsonUtils.toMap(command);
    payload.remove("traceId");
    payload.remove("operatorId");
    payload.remove("approvalId");
    payload.remove("replayOperationId");
    Object canonicalPayload = canonicalizePayload(payload);
    String canonicalPayloadJson = JsonUtils.toJson(canonicalPayload);
    return sha256(canonicalPayloadJson);
  }

  private Object canonicalizePayload(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((key, entryValue) -> {
        String normalizedKey = String.valueOf(key);
        Object canonicalEntryValue = canonicalizePayload(entryValue);
        sorted.put(normalizedKey, canonicalEntryValue);
      });
      return sorted;
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(this::canonicalizePayload).toList();
    }
    return value;
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
      byte[] digestBytes = digest.digest(valueBytes);
      return HexFormat.of().formatHex(digestBytes);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private BizException invalidPreview() {
    return BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.replay_preview_changed");
  }
}
