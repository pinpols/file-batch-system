package io.github.pinpols.batch.orchestrator.application.service.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import io.github.pinpols.batch.common.enums.ReplayOperationType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.SystemPipelineCatalog;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 解析并校验整 Workflow 重放已获批准的加密输入。 */
@Service
public class WorkflowRunReplaySnapshotResolver {

  private static final String INVALID_SNAPSHOT_MESSAGE =
      "error.workflow.replay_source_snapshot_invalid";
  private static final String RESULT_POLICY_PARAM = "_rerunResultPolicy";
  private static final String CONFIG_POLICY_PARAM = "_rerunConfigVersionPolicy";
  private static final String CONFIG_VERSION_PARAM = "_rerunConfigVersion";

  private final SecretPayloadProtector secretPayloadProtector;

  public WorkflowRunReplaySnapshotResolver(SecretPayloadProtector secretPayloadProtector) {
    this.secretPayloadProtector = secretPayloadProtector;
  }

  public Snapshot resolve(ReplayOperationEntity operation, String targetRequestId) {
    validateOperation(operation, targetRequestId);
    Map<String, Object> envelope = readMap(operation.snapshotPayload());
    String sealedPayload = requiredText(envelope.get("sealedSnapshotPayload"));
    String snapshotJson = reveal(sealedPayload);
    String actualSnapshotHash = sha256(snapshotJson);
    if (!actualSnapshotHash.equals(operation.snapshotHash())) {
      throw invalidSnapshot();
    }

    Map<String, Object> snapshot = readMap(snapshotJson);
    Map<String, Object> preview = readMap(snapshot.get("preview"));
    String sourceExecutionSnapshotJson = requiredText(snapshot.get("sourceExecutionSnapshot"));
    Map<String, Object> sourceExecutionSnapshot = readMap(sourceExecutionSnapshotJson);
    String protectedRootParams = requiredText(snapshot.get("sourceProtectedRootParams"));
    String rootParamsJson = reveal(protectedRootParams);
    Map<String, Object> rootParams = readMap(rootParamsJson);
    String tenantId = requiredText(preview.get("tenantId"));
    Long sourceWorkflowRunId = positiveLong(preview.get("sourceWorkflowRunId"));
    Long workflowDefinitionId = positiveLong(preview.get("workflowDefinitionId"));
    Integer workflowVersion = positiveInteger(preview.get("workflowVersion"));
    LocalDate bizDate = localDate(preview.get("bizDate"));
    String calendarCode = requiredText(preview.get("calendarCode"));
    String resultPolicy = requiredText(preview.get("resultPolicy"));
    String configVersionPolicy = requiredText(preview.get("configVersionPolicy"));
    Integer configVersion = nullableInteger(preview.get("configVersion"));
    Long singleJobDefinitionId = positiveLongOrNull(snapshot.get("singleJobDefinitionId"));
    Boolean dryRun = snapshot.get("dryRun") instanceof Boolean value ? value : null;
    Instant dataIntervalStart = instant(preview.get("dataIntervalStart"));
    Instant dataIntervalEnd = instant(preview.get("dataIntervalEnd"));
    boolean operationMatchesSnapshot = operation.tenantId().equals(tenantId)
        && operation.sourceWorkflowRunId().equals(sourceWorkflowRunId)
        && EmptyChecks.isNull(operation.fromDate())
        && EmptyChecks.isNull(operation.throughDate())
        && operation.calendarCode().equals(calendarCode)
        && operation.resultPolicy().equals(resultPolicy)
        && operation.configVersionPolicy().equals(configVersionPolicy)
        && Objects.equals(operation.configVersion(), configVersion);
    boolean operationSupportsFullReplay = operationMatchesSnapshot
        && "FULL_WORKFLOW".equals(operation.scope())
        && "REPLAY".equals(operation.executionMode());
    boolean targetRequestMatches = operationSupportsFullReplay
        && (EmptyChecks.isNull(operation.targetRequestId())
            || operation.targetRequestId().equals(targetRequestId));
    boolean snapshotHasDryRunValue = targetRequestMatches && EmptyChecks.isNotNull(dryRun);
    boolean sourceExecutionDefinitionMatches = false;
    if (snapshotHasDryRunValue) {
      Long sourceWorkflowDefinitionId =
          positiveLong(sourceExecutionSnapshot.get("workflowDefinitionId"));
      if (workflowDefinitionId.equals(sourceWorkflowDefinitionId)) {
        Integer sourceWorkflowVersion =
            positiveInteger(sourceExecutionSnapshot.get("workflowVersion"));
        if (workflowVersion.equals(sourceWorkflowVersion)) {
          sourceExecutionDefinitionMatches =
              calendarCode.equals(sourceExecutionSnapshot.get("workflowCalendarCode"));
        }
      }
    }
    boolean replaySnapshotIsValid = sourceExecutionDefinitionMatches
        && !(EmptyChecks.isNotNull(dataIntervalStart)
            && EmptyChecks.isNotNull(dataIntervalEnd)
            && !dataIntervalStart.isBefore(dataIntervalEnd))
        && (EmptyChecks.isNotNull(dataIntervalStart)) == (EmptyChecks.isNotNull(dataIntervalEnd));
    if (!replaySnapshotIsValid) {
      throw invalidSnapshot();
    }

    String executionSnapshotJson =
        restoreExecutionSnapshot(operation, targetRequestId, snapshot, sourceExecutionSnapshot);
    WorkflowRunEntity snapshotRun = new WorkflowRunEntity();
    snapshotRun.setTenantId(tenantId);
    snapshotRun.setWorkflowDefinitionId(workflowDefinitionId);
    snapshotRun.setWorkflowVersionSnapshot(workflowVersion);
    snapshotRun.setExecutionSnapshot(executionSnapshotJson);
    WorkflowRunExecutionSnapshot restoredSnapshot = WorkflowRunExecutionSnapshot.from(snapshotRun);
    if (EmptyChecks.isNull(restoredSnapshot)) {
      throw invalidSnapshot();
    }

    Map<String, Object> launchParams = new LinkedHashMap<>(rootParams);
    launchParams.put(RESULT_POLICY_PARAM, resultPolicy);
    launchParams.put(CONFIG_POLICY_PARAM, configVersionPolicy);
    if (EmptyChecks.isNull(configVersion)) {
      launchParams.remove(CONFIG_VERSION_PARAM);
    } else {
      launchParams.put(CONFIG_VERSION_PARAM, configVersion);
    }
    return new Snapshot(
        tenantId,
        workflowDefinitionId,
        workflowVersion,
        calendarCode,
        bizDate,
        dataIntervalStart,
        dataIntervalEnd,
        dryRun,
        singleJobDefinitionId,
        launchParams,
        executionSnapshotJson);
  }

  private String restoreExecutionSnapshot(
      ReplayOperationEntity operation,
      String targetRequestId,
      Map<String, Object> snapshot,
      Map<String, Object> sourceExecutionSnapshot) {
    Map<String, Object> selectedCrossDayDependencyResultVersionIds =
        optionalMap(snapshot, "selectedCrossDayDependencyResultVersionIdsByNode");
    Map<String, Object> selectedDefinitions = readMap(snapshot.get("selectedJobDefinitions"));
    if (!selectedDefinitions
        .keySet()
        .containsAll(selectedCrossDayDependencyResultVersionIds.keySet())) {
      throw invalidSnapshot();
    }
    String operationTenantId = operation.tenantId();
    Map<String, Object> selectedFileTemplateSnapshots =
        optionalMap(snapshot, "selectedFileTemplateSnapshots");
    Map<String, Object> selectedDispatchChannelSnapshots =
        optionalMap(snapshot, "selectedDispatchChannelSnapshots");
    Map<String, Object> selectedDispatchSecretBindings =
        optionalMap(snapshot, "selectedDispatchSecretBindings");
    Map<String, Object> selectedReplayCapabilities =
        readMap(snapshot.get("selectedReplayCapabilities"));
    SelectedDefinitions selected = new SelectedDefinitions(
        selectedDefinitions,
        selectedFileTemplateSnapshots,
        selectedDispatchChannelSnapshots,
        selectedDispatchSecretBindings,
        selectedReplayCapabilities);
    Long replayOperationId = operation.id();
    Map<String, Object> executionSnapshot = freezeSelectedDefinitions(
        operationTenantId, sourceExecutionSnapshot, selected, replayOperationId);
    executionSnapshot.put(
        "crossDayDependencyResultVersionIdsByNode", selectedCrossDayDependencyResultVersionIds);
    executionSnapshot.put("triggerRequestId", targetRequestId);
    executionSnapshot.put("replaySessionId", null);
    executionSnapshot.put("replayOperationId", operation.id());
    executionSnapshot.put("sourceWorkflowRunId", operation.sourceWorkflowRunId());
    return JsonUtils.toJson(executionSnapshot);
  }

  private void validateOperation(ReplayOperationEntity operation, String targetRequestId) {
    boolean replayOperationSnapshotIsInvalid = EmptyChecks.isNull(operation)
        || !ReplayOperationType.WORKFLOW_RUN.code().equals(operation.operationType())
        || EmptyChecks.isNull(operation.id())
        || EmptyChecks.isBlank(operation.tenantId())
        || EmptyChecks.isNull(operation.sourceWorkflowRunId())
        || EmptyChecks.isNotNull(operation.fromDate())
        || EmptyChecks.isNotNull(operation.throughDate())
        || EmptyChecks.isBlank(operation.calendarCode())
        || EmptyChecks.isBlank(operation.scope())
        || EmptyChecks.isBlank(operation.executionMode())
        || EmptyChecks.isBlank(operation.resultPolicy())
        || EmptyChecks.isBlank(operation.configVersionPolicy())
        || EmptyChecks.isBlank(operation.snapshotHash())
        || EmptyChecks.isBlank(operation.snapshotPayload())
        || EmptyChecks.isBlank(targetRequestId);
    if (replayOperationSnapshotIsInvalid) {
      throw invalidSnapshot();
    }
  }

  private Map<String, Object> freezeSelectedDefinitions(
      String tenantId,
      Map<String, Object> sourceExecutionSnapshot,
      SelectedDefinitions selected,
      Long replayOperationId) {
    Map<String, Object> selectedDefinitions = selected.definitions();
    Map<String, Object> selectedFileTemplateSnapshots = selected.fileTemplateSnapshots();
    Map<String, Object> selectedDispatchChannelSnapshots = selected.dispatchChannelSnapshots();
    Map<String, Object> selectedDispatchSecretBindings = selected.dispatchSecretBindings();
    Map<String, Object> selectedReplayCapabilities = selected.replayCapabilities();
    boolean selectedSnapshotsReferenceUnknownJobs =
        !selectedDefinitions.keySet().containsAll(selectedFileTemplateSnapshots.keySet())
            || !selectedDefinitions.keySet().containsAll(selectedDispatchChannelSnapshots.keySet())
            || !selectedDefinitions.keySet().containsAll(selectedDispatchSecretBindings.keySet());
    if (selectedSnapshotsReferenceUnknownJobs) {
      throw invalidSnapshot();
    }
    Map<String, Object> frozenDefinitions = readMap(sourceExecutionSnapshot.get("jobDefinitions"));
    for (Map.Entry<String, Object> entry : selectedDefinitions.entrySet()) {
      JobDefinitionEntity definition = readDefinition(entry.getValue());
      boolean replayDefinitionIdentityIsInvalid = !tenantId.equals(definition.tenantId())
          || EmptyChecks.isNull(definition.id())
          || EmptyChecks.isBlank(definition.jobCode())
          || EmptyChecks.isBlank(definition.jobType());
      if (replayDefinitionIdentityIsInvalid) {
        throw invalidSnapshot();
      }
      Map<String, Object> identity = new LinkedHashMap<>();
      identity.put("jobDefinitionId", definition.id());
      identity.put("jobCode", definition.jobCode());
      identity.put("jobType", definition.jobType());
      identity.put("jobDefinitionVersion", definition.version());
      SystemPipelineCatalog.forJobType(definition.jobType()).ifPresent(pipeline -> {
        identity.put("systemPipelineCode", pipeline.code());
        identity.put("systemPipelineVersion", pipeline.semanticVersion());
      });
      identity.put("executionDefinition", definition);
      if (selectedFileTemplateSnapshots.containsKey(entry.getKey())) {
        if (!supportsFileTemplateReplay(definition.jobType())) {
          throw invalidSnapshot();
        }
        Object rawFileTemplateSnapshots = selectedFileTemplateSnapshots.get(entry.getKey());
        Map<String, String> fileTemplateSnapshots =
            readProtectedFileTemplateSnapshots(rawFileTemplateSnapshots);
        identity.put("frozenFileTemplateSnapshots", fileTemplateSnapshots);
      }
      if (selectedDispatchChannelSnapshots.containsKey(entry.getKey())) {
        if (!"DISPATCH".equals(definition.jobType())) {
          throw invalidSnapshot();
        }
        Object rawDispatchChannelSnapshots = selectedDispatchChannelSnapshots.get(entry.getKey());
        Map<String, String> dispatchChannelSnapshots =
            readProtectedDispatchChannelSnapshots(rawDispatchChannelSnapshots);
        identity.put("frozenDispatchChannelSnapshots", dispatchChannelSnapshots);
      }
      if (selectedDispatchSecretBindings.containsKey(entry.getKey())) {
        if (!"DISPATCH".equals(definition.jobType())
            || !selectedDispatchChannelSnapshots.containsKey(entry.getKey())) {
          throw invalidSnapshot();
        }
        Object rawDispatchSecretBindings = selectedDispatchSecretBindings.get(entry.getKey());
        Map<String, Object> dispatchSecretBindings =
            readDispatchSecretBindings(rawDispatchSecretBindings);
        identity.put("dispatchSecretBindings", dispatchSecretBindings);
      }
      Object rawDeclaration = selectedReplayCapabilities.get(entry.getKey());
      if (EmptyChecks.isNotNull(rawDeclaration)) {
        ReplayCapabilityDeclaration declaration = readReplayCapability(rawDeclaration);
        identity.put(
            "replayExecutionGuard", new ReplayExecutionGuard(replayOperationId, declaration));
      }
      frozenDefinitions.put(entry.getKey(), identity);
    }
    sourceExecutionSnapshot.put("jobDefinitions", frozenDefinitions);
    return sourceExecutionSnapshot;
  }

  private boolean supportsFileTemplateReplay(String jobType) {
    return "IMPORT".equals(jobType)
        || "EXPORT".equals(jobType)
        || "BUNDLE_IMPORT".equals(jobType)
        || "BUNDLE_EXPORT".equals(jobType);
  }

  private Map<String, String> readProtectedFileTemplateSnapshots(Object rawSnapshots) {
    Map<String, Object> snapshots = readMap(rawSnapshots);
    if (EmptyChecks.isEmpty(Objects.requireNonNull(snapshots))) {
      throw invalidSnapshot();
    }
    Map<String, String> protectedSnapshots = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : snapshots.entrySet()) {
      String templateCode = entry.getKey();
      String protectedSnapshot = requiredText(entry.getValue());
      if (EmptyChecks.isBlank(templateCode)) {
        throw invalidSnapshot();
      }
      protectedSnapshots.put(templateCode, protectedSnapshot);
    }
    return protectedSnapshots;
  }

  private Map<String, String> readProtectedDispatchChannelSnapshots(Object rawSnapshots) {
    return readProtectedFileTemplateSnapshots(rawSnapshots);
  }

  private Map<String, Object> readDispatchSecretBindings(Object rawBindings) {
    Map<String, Object> bindings = readMap(rawBindings);
    Map<String, Object> validatedBindings = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : bindings.entrySet()) {
      if (EmptyChecks.isBlank(entry.getKey())) {
        throw invalidSnapshot();
      }
      Map<String, Object> binding = readMap(entry.getValue());
      validatedBindings.put(entry.getKey(), binding);
    }
    return validatedBindings;
  }

  private Map<String, Object> optionalMap(Map<String, Object> source, String key) {
    return source.containsKey(key) ? readMap(source.get(key)) : Map.of();
  }

  private JobDefinitionEntity readDefinition(Object value) {
    try {
      String json = JsonUtils.toJson(value);
      JobDefinitionEntity definition = JsonUtils.fromJson(json, JobDefinitionEntity.class);
      if (EmptyChecks.isNull(definition)) {
        throw invalidSnapshot();
      }
      return definition;
    } catch (BizException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalidSnapshot();
    }
  }

  private ReplayCapabilityDeclaration readReplayCapability(Object value) {
    try {
      String json = JsonUtils.toJson(value);
      ReplayCapabilityDeclaration declaration =
          JsonUtils.fromJson(json, ReplayCapabilityDeclaration.class);
      boolean replayDeclarationIsUnsafe = EmptyChecks.isNull(declaration)
          || !declaration.isFullyDeclared()
          || declaration.idempotencyCapability() == IdempotencyCapability.NONE;
      if (replayDeclarationIsUnsafe) {
        throw invalidSnapshot();
      }
      return declaration;
    } catch (BizException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalidSnapshot();
    }
  }

  private Map<String, Object> readMap(Object value) {
    try {
      String json = value instanceof String jsonText ? jsonText : JsonUtils.toJson(value);
      Map<String, Object> map = JsonUtils.fromJson(json, new TypeReference<>() {});
      if (EmptyChecks.isNull(map)) {
        throw invalidSnapshot();
      }
      return new LinkedHashMap<>(map);
    } catch (BizException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalidSnapshot();
    }
  }

  private String reveal(String protectedPayload) {
    try {
      return secretPayloadProtector.revealEncrypted(protectedPayload);
    } catch (RuntimeException exception) {
      throw invalidSnapshot();
    }
  }

  private String requiredText(Object value) {
    if (!(value instanceof String text) || EmptyChecks.isBlank(text)) {
      throw invalidSnapshot();
    }
    return text;
  }

  private Long positiveLong(Object value) {
    Long result = positiveLongOrNull(value);
    if (EmptyChecks.isNull(result)) {
      throw invalidSnapshot();
    }
    return result;
  }

  private Long positiveLongOrNull(Object value) {
    if (value instanceof Number number && number.longValue() > 0) {
      return number.longValue();
    }
    return null;
  }

  private Integer positiveInteger(Object value) {
    Integer result = nullableInteger(value);
    if (EmptyChecks.isNull(result) || result <= 0) {
      throw invalidSnapshot();
    }
    return result;
  }

  private Integer nullableInteger(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private LocalDate localDate(Object value) {
    if (value instanceof String || value instanceof List<?>) {
      try {
        if (value instanceof String text) {
          return LocalDate.parse(text);
        }
        String json = JsonUtils.toJson(value);
        return JsonUtils.fromJson(json, LocalDate.class);
      } catch (RuntimeException invalidDate) {
        throw invalidSnapshot();
      }
    }
    throw invalidSnapshot();
  }

  private Instant instant(Object value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    if (value instanceof String || value instanceof Number) {
      try {
        if (value instanceof String text) {
          return Instant.parse(text);
        }
        String json = JsonUtils.toJson(value);
        return JsonUtils.fromJson(json, Instant.class);
      } catch (RuntimeException invalidInstant) {
        throw invalidSnapshot();
      }
    }
    throw invalidSnapshot();
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

  private BizException invalidSnapshot() {
    return BizException.of(ResultCode.STATE_CONFLICT, INVALID_SNAPSHOT_MESSAGE);
  }

  /** 同一已批准快照中的作业配置及其绑定，不能重新读取实时配置。 */
  private record SelectedDefinitions(
      Map<String, Object> definitions,
      Map<String, Object> fileTemplateSnapshots,
      Map<String, Object> dispatchChannelSnapshots,
      Map<String, Object> dispatchSecretBindings,
      Map<String, Object> replayCapabilities) {

    private SelectedDefinitions {
      Objects.requireNonNull(definitions, "definitions");
      Objects.requireNonNull(fileTemplateSnapshots, "fileTemplateSnapshots");
      Objects.requireNonNull(dispatchChannelSnapshots, "dispatchChannelSnapshots");
      Objects.requireNonNull(dispatchSecretBindings, "dispatchSecretBindings");
      Objects.requireNonNull(replayCapabilities, "replayCapabilities");
    }

    @Override
    public String toString() {
      return "SelectedDefinitions[<redacted>]";
    }
  }

  public record Snapshot(
      String tenantId,
      Long workflowDefinitionId,
      Integer workflowVersion,
      String calendarCode,
      LocalDate bizDate,
      Instant dataIntervalStart,
      Instant dataIntervalEnd,
      Boolean dryRun,
      Long singleJobDefinitionId,
      Map<String, Object> launchParams,
      String executionSnapshotJson) {

    @Override
    public String toString() {
      return "Snapshot[tenantId=" + tenantId + ", workflowDefinitionId=" + workflowDefinitionId
          + ", workflowVersion=" + workflowVersion + ", bizDate=" + bizDate
          + ", launchParams=<redacted>]";
    }
  }
}
