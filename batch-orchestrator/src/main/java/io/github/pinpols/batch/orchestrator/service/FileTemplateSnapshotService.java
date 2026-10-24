package io.github.pinpols.batch.orchestrator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.FrozenFileTemplateSnapshot;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ReplayOperationType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.plan.BundlePlanParams;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.FileTemplateSnapshotMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchValidationService.WorkflowReplayExecutionBinding;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Captures template configuration in the Job creation transaction for dispatch and recovery. */
@Service
@RequiredArgsConstructor
public class FileTemplateSnapshotService {

  private final FileTemplateSnapshotMapper templates;
  private final JobInstanceMapper instances;
  private final SecretPayloadProtector protector;

  public Map<String, Object> pin(
      LaunchRequest request, JobDefinitionEntity definition, Map<String, Object> parameters) {
    Map<String, Object> pinned = new LinkedHashMap<>(parameters);
    pinned.remove(FrozenFileTemplateSnapshot.PAYLOAD_KEY);
    pinned.remove(FrozenFileTemplateSnapshot.COLLECTION_KEY);
    JobType type = DictEnum.fromCode(JobType.class, definition.jobType());
    if (EmptyChecks.isNull(type)
        || !Set.of(JobType.IMPORT, JobType.EXPORT, JobType.BUNDLE_IMPORT, JobType.BUNDLE_EXPORT)
            .contains(type)) {
      return pinned;
    }
    if (ReplayOperationType.WORKFLOW_RUN.code().equals(parameters.get("operationType"))) {
      // Whole-graph admission must provide its persisted, approved per-node snapshot first.
      throw invalidSnapshot();
    }
    Set<String> codes = templateCodes(type, definition.jobCode(), pinned);
    if (EmptyChecks.isEmpty(codes)) {
      return pinned;
    }
    ConfigVersionPolicy policy = configPolicy(parameters);
    Map<?, ?> original = policy == ConfigVersionPolicy.USE_ORIGINAL_CONFIG
        ? sourceParameters(request, definition, parameters)
        : Map.of();
    if (policy == ConfigVersionPolicy.USE_SPECIFIED_VERSION) {
      // A Job definition version is not a file-template version. Never infer equality.
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.rerun.file_template_version_unavailable");
    }
    Map<String, Object> snapshots = new LinkedHashMap<>();
    for (String code : codes) {
      String protectedSnapshot = policy == ConfigVersionPolicy.USE_ORIGINAL_CONFIG
          ? originalSnapshot(request.tenantId(), code, type.workerTypeCode(), original)
          : captureLatest(request.tenantId(), code, type.workerTypeCode());
      if (EmptyChecks.isNotNull(protectedSnapshot)) {
        snapshots.put(code, protectedSnapshot);
      }
    }
    if (type.isBundle()) {
      if (EmptyChecks.isNotEmpty(snapshots)) {
        pinned.put(FrozenFileTemplateSnapshot.COLLECTION_KEY, snapshots);
      }
    } else if (EmptyChecks.isNotEmpty(snapshots)) {
      Object firstPayloadSnapshot = snapshots.values().iterator().next();
      pinned.put(
          FrozenFileTemplateSnapshot.PAYLOAD_KEY, firstPayloadSnapshot);
    }
    return pinned;
  }

  /** Applies only the template snapshots bound to this approved replay node. */
  public Map<String, Object> pinWorkflowReplay(
      LaunchRequest request,
      JobDefinitionEntity definition,
      Map<String, Object> parameters,
      WorkflowReplayExecutionBinding binding) {
    Map<String, Object> pinned = new LinkedHashMap<>(parameters);
    pinned.remove(FrozenFileTemplateSnapshot.PAYLOAD_KEY);
    pinned.remove(FrozenFileTemplateSnapshot.COLLECTION_KEY);
    validateReplayBinding(request, definition, pinned, binding);
    JobType type = DictEnum.fromCode(JobType.class, definition.jobType());
    if (EmptyChecks.isNull(type)
        || !Set.of(JobType.IMPORT, JobType.EXPORT, JobType.BUNDLE_IMPORT, JobType.BUNDLE_EXPORT)
            .contains(type)) {
      if (EmptyChecks.isNotEmpty(binding.protectedFileTemplateSnapshots())) {
        throw invalidSnapshot();
      }
      return pinned;
    }

    Set<String> codes = templateCodes(type, definition.jobCode(), pinned);
    Map<String, String> protectedSnapshots = binding.protectedFileTemplateSnapshots();
    if (EmptyChecks.isEmpty(codes) || !codes.equals(protectedSnapshots.keySet())) {
      throw invalidSnapshot();
    }
    protectedSnapshots.forEach((code, protectedSnapshot) ->
        validate(protectedSnapshot, request.tenantId(), code, type.workerTypeCode()));
    if (type.isBundle()) {
      pinned.put(FrozenFileTemplateSnapshot.COLLECTION_KEY, protectedSnapshots);
    } else {
      if (protectedSnapshots.size() != 1) {
        throw invalidSnapshot();
      }
      pinned.put(
          FrozenFileTemplateSnapshot.PAYLOAD_KEY,
          protectedSnapshots.values().iterator().next());
    }
    return pinned;
  }

  /**
   * Captures the exact file-template inputs that a whole-Workflow replay preview assessed.
   * Unlike ordinary launch capture, replay never falls back to the latest template when a
   * requested or original template cannot be proven.
   */
  public ReplayTemplateSelection captureForWorkflowReplay(
      String tenantId,
      JobDefinitionEntity definition,
      Map<String, Object> effectiveParams,
      ConfigVersionPolicy policy,
      Map<?, ?> originalEffectiveParams) {
    JobType type = DictEnum.fromCode(JobType.class, definition.jobType());
    if (EmptyChecks.isNull(type)
        || !Set.of(JobType.IMPORT, JobType.EXPORT, JobType.BUNDLE_IMPORT, JobType.BUNDLE_EXPORT)
            .contains(type)) {
      return new ReplayTemplateSelection(Map.of(), null);
    }
    if (!tenantId.equals(definition.tenantId())
        || EmptyChecks.isBlank(definition.jobCode())
        || policy == ConfigVersionPolicy.USE_SPECIFIED_VERSION) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.rerun.file_template_version_unavailable");
    }
    Set<String> codes = templateCodes(type, definition.jobCode(), effectiveParams);
    if (EmptyChecks.isEmpty(codes)) {
      throw invalidSnapshot();
    }

    Map<String, String> protectedSnapshots = new LinkedHashMap<>();
    Set<String> selectedPlugins = new LinkedHashSet<>();
    boolean everyTemplateSelectsPlugin = true;
    for (String code : codes) {
      String protectedSnapshot = policy == ConfigVersionPolicy.USE_ORIGINAL_CONFIG
          ? originalSnapshot(tenantId, code, type.workerTypeCode(), originalEffectiveParams)
          : captureLatest(tenantId, code, type.workerTypeCode());
      if (EmptyChecks.isBlank(protectedSnapshot)) {
        throw invalidSnapshot();
      }
      FrozenFileTemplateSnapshot frozen = reveal(protectedSnapshot, tenantId, code, type);
      protectedSnapshots.put(code, protectedSnapshot);
      String pluginId = resolveReplayPlugin(type.workerTypeCode(), frozen.configuration());
      if (EmptyChecks.isNotBlank(pluginId)) {
        selectedPlugins.add(pluginId);
      } else {
        everyTemplateSelectsPlugin = false;
      }
    }
    String pluginId = everyTemplateSelectsPlugin && selectedPlugins.size() == 1
        ? selectedPlugins.iterator().next()
        : null;
    return new ReplayTemplateSelection(Map.copyOf(protectedSnapshots), pluginId);
  }

  private ConfigVersionPolicy configPolicy(Map<String, Object> parameters) {
    Object explicit = parameters.get("_rerunConfigVersionPolicy");
    if (EmptyChecks.isNull(explicit)) {
      return "JOB_RERUN".equals(parameters.get("operationType"))
          ? ConfigVersionPolicy.USE_ORIGINAL_CONFIG
          : ConfigVersionPolicy.USE_LATEST_CONFIG;
    }
    ConfigVersionPolicy policy = ConfigVersionPolicy.fromCodeOrNull(String.valueOf(explicit));
    if (EmptyChecks.isNull(policy)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.rerun.config_version_policy_invalid");
    }
    return policy;
  }

  private Set<String> templateCodes(JobType type, String jobCode, Map<String, Object> parameters) {
    Set<String> codes = new LinkedHashSet<>();
    if (type.isBundle()) {
      BundlePlanParams.extract(parameters, type).forEach(file -> codes.add(file.templateCode()));
      return codes;
    }
    Object value = parameters.get("templateCode");
    if (value instanceof String code && EmptyChecks.isNotBlank(code)) {
      codes.add(code);
    } else if (type == JobType.EXPORT) {
      codes.add(jobCode + "_TPL");
    }
    return codes;
  }

  private String captureLatest(String tenantId, String code, String type) {
    String configurationJson = templates.selectLatestConfiguration(tenantId, code, type);
    if (EmptyChecks.isBlank(configurationJson)) {
      // Ordinary payload-only execution previously allowed no matching template.
      return null;
    }
    Map<String, Object> configuration =
        JsonUtils.fromJson(configurationJson, new TypeReference<Map<String, Object>>() {});
    FrozenFileTemplateSnapshot snapshot = FrozenFileTemplateSnapshot.capture(configuration);
    snapshot.requireIdentity(tenantId, code, type);
    String plaintext = JsonUtils.toJson(snapshot);
    String protectedSnapshot = protector.protect(plaintext);
    // Refuse a disabled encryption provider before persisting an unauthenticated snapshot.
    validate(protectedSnapshot, tenantId, code, type);
    return protectedSnapshot;
  }

  private Map<?, ?> sourceParameters(
      LaunchRequest request, JobDefinitionEntity definition, Map<String, Object> parameters) {
    JobInstanceEntity source;
    try {
      String sourceIdText = String.valueOf(parameters.get("parentInstanceId"));
      BigDecimal sourceIdDecimal = new BigDecimal(sourceIdText);
      long sourceId = sourceIdDecimal.longValueExact();
      source = sourceId > 0 ? instances.selectById(request.tenantId(), sourceId) : null;
    } catch (NumberFormatException | ArithmeticException invalidIdentity) {
      throw invalidSnapshot();
    }
    boolean sourceSnapshotMatchesDispatch =
        EmptyChecks.isNotNull(source)
            && request.tenantId().equals(source.getTenantId())
            && definition.jobCode().equals(source.getJobCode())
            && definition.id().equals(source.getJobDefinitionId())
            && Objects.equals(request.bizDate(), source.getBizDate())
            && !EmptyChecks.isBlank(source.getParamsSnapshot());
    if (!sourceSnapshotMatchesDispatch) {
      throw invalidSnapshot();
    }
    try {
      Map<String, Object> snapshot = JsonUtils.fromJson(
          source.getParamsSnapshot(), new TypeReference<Map<String, Object>>() {});
      if (snapshot.get("effectiveParams") instanceof Map<?, ?> effective) {
        return effective;
      }
    } catch (RuntimeException invalidSourceSnapshot) {
      throw invalidSnapshot();
    }
    throw invalidSnapshot();
  }

  private String originalSnapshot(String tenantId, String code, String type, Map<?, ?> original) {
    Object value = original.get(FrozenFileTemplateSnapshot.PAYLOAD_KEY);
    if (original.get(FrozenFileTemplateSnapshot.COLLECTION_KEY) instanceof Map<?, ?> collection) {
      value = collection.get(code);
    }
    if (!(value instanceof String protectedSnapshot)) {
      throw invalidSnapshot();
    }
    validate(protectedSnapshot, tenantId, code, type);
    return protectedSnapshot;
  }

  private FrozenFileTemplateSnapshot reveal(
      String protectedSnapshot, String tenantId, String code, JobType type) {
    try {
      String plaintext = protector.revealEncrypted(protectedSnapshot);
      FrozenFileTemplateSnapshot snapshot =
          JsonUtils.fromJsonStrict(plaintext, FrozenFileTemplateSnapshot.class);
      snapshot.requireIdentity(tenantId, code, type.workerTypeCode());
      return snapshot;
    } catch (RuntimeException invalidSnapshot) {
      throw invalidSnapshot();
    }
  }

  private String resolveReplayPlugin(String workerType, Map<String, Object> configuration) {
    if (JobType.IMPORT.workerTypeCode().equals(workerType)) {
      Object configuredPlugin = configuration.get("load_target_ref");
      return configuredPlugin instanceof String text && EmptyChecks.isNotBlank(text)
          ? text.trim()
          : WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED;
    }
    if (JobType.EXPORT.workerTypeCode().equals(workerType)) {
      Object configuredPlugin = configuration.get("export_data_ref");
      if (configuredPlugin instanceof String text && EmptyChecks.isNotBlank(text)) {
        return text.trim();
      }
      Object rawSchema = configuration.get("query_param_schema");
      if (rawSchema instanceof Map<?, ?> schema) {
        Object nestedPlugin = schema.get("export_data_ref");
        if (nestedPlugin instanceof String text && EmptyChecks.isNotBlank(text)) {
          return text.trim();
        }
      }
    }
    return null;
  }

  private void validate(String protectedSnapshot, String tenantId, String code, String type) {
    try {
      String plaintext = protector.revealEncrypted(protectedSnapshot);
      FrozenFileTemplateSnapshot snapshot =
          JsonUtils.fromJsonStrict(plaintext, FrozenFileTemplateSnapshot.class);
      snapshot.requireIdentity(tenantId, code, type);
    } catch (RuntimeException invalidSnapshot) {
      throw invalidSnapshot();
    }
  }

  private BizException invalidSnapshot() {
    return BizException.of(ResultCode.STATE_CONFLICT, "error.worker.frozen_file_template_invalid");
  }

  private void validateReplayBinding(
      LaunchRequest request,
      JobDefinitionEntity definition,
      Map<String, Object> parameters,
      WorkflowReplayExecutionBinding binding) {
    ReplayExecutionGuard guard = EmptyChecks.isNull(binding) ? null : binding.executionGuard();
    boolean bindingIsIncomplete = EmptyChecks.isNull(binding)
        || EmptyChecks.isNull(binding.replayOperationId())
        || binding.replayOperationId() <= 0
        || EmptyChecks.isNull(binding.workflowRunId())
        || binding.workflowRunId() <= 0
        || EmptyChecks.isBlank(binding.nodeCode())
        || EmptyChecks.isNull(guard);
    if (bindingIsIncomplete) {
      throw invalidSnapshot();
    }
    boolean replayGuardDoesNotMatchBinding =
        !binding.replayOperationId().equals(guard.replayOperationId());
    if (replayGuardDoesNotMatchBinding) {
      throw invalidSnapshot();
    }
    boolean requestDoesNotMatchReplayIdentity = EmptyChecks.isNull(request)
        || EmptyChecks.isNull(definition)
        || EmptyChecks.isBlank(request.tenantId())
        || EmptyChecks.isBlank(request.jobCode())
        || !request.tenantId().equals(definition.tenantId())
        || !request.jobCode().equals(definition.jobCode());
    if (requestDoesNotMatchReplayIdentity) {
      throw invalidSnapshot();
    }
    boolean workflowExecutionMetadataDoesNotMatchBinding =
        !binding.workflowRunId().equals(positiveLong(parameters.get("_parentWorkflowRunId")))
        || !binding.nodeCode().equals(parameters.get("_parentNodeCode"))
        || !ReplayOperationType.WORKFLOW_RUN.code().equals(parameters.get("operationType"));
    if (workflowExecutionMetadataDoesNotMatchBinding) {
      throw invalidSnapshot();
    }
  }

  private Long positiveLong(Object value) {
    try {
      String numericText = String.valueOf(value);
      BigDecimal exactValue = new BigDecimal(numericText);
      long parsed = exactValue.longValueExact();
      return parsed > 0 ? parsed : null;
    } catch (NumberFormatException | ArithmeticException invalidIdentity) {
      return null;
    }
  }

  public record ReplayTemplateSelection(Map<String, String> protectedSnapshots, String pluginId) {}
}
