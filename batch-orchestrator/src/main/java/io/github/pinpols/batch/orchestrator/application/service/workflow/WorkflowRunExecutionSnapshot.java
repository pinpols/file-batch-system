package io.github.pinpols.batch.orchestrator.application.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.SystemPipelineCatalog;
import io.github.pinpols.batch.common.enums.SystemPipelineDefinition;
import io.github.pinpols.batch.common.enums.SystemPipelineTaskKeys;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.domain.workflow.CrossDayDependencySpec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;

/** 创建 Workflow Run 时捕获的不可变定义输入。 */
public record WorkflowRunExecutionSnapshot(
    Integer schemaVersion,
    Long workflowDefinitionId,
    Integer workflowVersion,
    String workflowCalendarCode,
    Long replaySessionId,
    Long replayOperationId,
    List<WorkflowNodeEntity> nodes,
    List<WorkflowEdgeEntity> edges,
    Map<String, JobDefinitionEntity> jobDefinitions,
    Map<String, SystemPipelineIdentity> systemPipelines,
    Map<String, ReplayExecutionGuard> replayExecutionGuards,
    Map<String, Map<String, String>> frozenFileTemplateSnapshots,
    Map<String, Map<String, String>> frozenDispatchChannelSnapshots,
    Map<String, Map<String, Object>> dispatchSecretBindings,
    Map<String, Map<String, List<Long>>> crossDayDependencyResultVersionIdsByNode) {

  public record SystemPipelineIdentity(String code, Integer version) {}

  public static WorkflowRunExecutionSnapshot from(WorkflowRunEntity run) {
    if (EmptyChecks.isNull(run)
        || EmptyChecks.isNull(run.getExecutionSnapshot())
        || EmptyChecks.isBlank(run.getExecutionSnapshot())) {
      return null;
    }
    Map<String, Object> raw =
        JsonUtils.fromJson(run.getExecutionSnapshot(), new TypeReference<Map<String, Object>>() {});
    Integer schemaVersion = number(raw.get("schemaVersion"));
    // Schema v1 只保存了启动标识，并未冻结图结构。旧运行仍走
    // 旧定义读取路径，不能把 v1 的空图当成权威快照。
    if (EmptyChecks.isNull(schemaVersion)) {
      throw invalidSnapshot();
    }
    if (schemaVersion < 2) {
      return null;
    }
    if (schemaVersion != 2) {
      throw invalidSnapshot();
    }
    List<WorkflowNodeEntity> nodes = readList(raw.get("nodes"), WorkflowNodeEntity.class);
    List<WorkflowEdgeEntity> edges = readList(raw.get("edges"), WorkflowEdgeEntity.class);
    Map<String, JobDefinitionEntity> jobs = new LinkedHashMap<>();
    Map<String, SystemPipelineIdentity> pipelines = new LinkedHashMap<>();
    Map<String, ReplayExecutionGuard> replayExecutionGuards = new LinkedHashMap<>();
    Map<String, Map<String, String>> frozenFileTemplateSnapshots = new LinkedHashMap<>();
    Map<String, Map<String, String>> frozenDispatchChannelSnapshots = new LinkedHashMap<>();
    Map<String, Map<String, Object>> dispatchSecretBindings = new LinkedHashMap<>();
    Object rawCrossDayDependencyResultVersionIds =
        raw.get("crossDayDependencyResultVersionIdsByNode");
    Map<String, Map<String, List<Long>>> crossDayDependencyResultVersionIdsByNode =
        readCrossDayDependencyResultVersionIds(rawCrossDayDependencyResultVersionIds);
    Map<String, Map<?, ?>> jobMetadata = new LinkedHashMap<>();
    Object rawJobs = raw.get("jobDefinitions");
    if (rawJobs instanceof Map<?, ?> entries) {
      entries.forEach((key, value) -> {
        if (key instanceof String nodeCode && value instanceof Map<?, ?> jobEntry) {
          jobMetadata.put(nodeCode, jobEntry);
          Object definition = jobEntry.get("executionDefinition");
          if (EmptyChecks.isNotNull(definition)) {
            String serializedDefinition = JsonUtils.toJson(definition);
            JobDefinitionEntity job =
                JsonUtils.fromJson(serializedDefinition, JobDefinitionEntity.class);
            if (EmptyChecks.isNotNull(job)) {
              jobs.put(nodeCode, job);
            }
          }
          String pipelineCode = string(jobEntry.get("systemPipelineCode"));
          Integer pipelineVersion = number(jobEntry.get("systemPipelineVersion"));
          if (EmptyChecks.isNotNull(pipelineCode) && EmptyChecks.isNotNull(pipelineVersion)) {
            pipelines.put(nodeCode, new SystemPipelineIdentity(pipelineCode, pipelineVersion));
          }
          ReplayExecutionGuard replayGuard =
              readReplayExecutionGuard(jobEntry.get("replayExecutionGuard"));
          if (EmptyChecks.isNotNull(replayGuard)) {
            replayExecutionGuards.put(nodeCode, replayGuard);
          }
          if (jobEntry.containsKey("frozenFileTemplateSnapshots")) {
            Object rawFileTemplateSnapshots = jobEntry.get("frozenFileTemplateSnapshots");
            Map<String, String> fileTemplateSnapshots =
                readProtectedTemplateSnapshots(rawFileTemplateSnapshots);
            frozenFileTemplateSnapshots.put(nodeCode, fileTemplateSnapshots);
          }
          if (jobEntry.containsKey("frozenDispatchChannelSnapshots")) {
            Object rawDispatchChannelSnapshots = jobEntry.get("frozenDispatchChannelSnapshots");
            Map<String, String> dispatchChannelSnapshots =
                readProtectedDispatchChannelSnapshots(rawDispatchChannelSnapshots);
            frozenDispatchChannelSnapshots.put(nodeCode, dispatchChannelSnapshots);
          }
          if (jobEntry.containsKey("dispatchSecretBindings")) {
            Object rawDispatchSecretBindings = jobEntry.get("dispatchSecretBindings");
            Map<String, Object> parsedDispatchSecretBindings =
                readDispatchSecretBindings(rawDispatchSecretBindings);
            dispatchSecretBindings.put(nodeCode, parsedDispatchSecretBindings);
          }
        }
      });
    }
    Long workflowDefinitionId = longNumber(raw.get("workflowDefinitionId"));
    Integer workflowVersion = number(raw.get("workflowVersion"));
    String workflowCalendarCode = string(raw.get("workflowCalendarCode"));
    Long replaySessionId = longNumber(raw.get("replaySessionId"));
    Long replayOperationId = longNumber(raw.get("replayOperationId"));
    if (EmptyChecks.isNotNull(replayOperationId) && replayOperationId <= 0) {
      throw invalidSnapshot();
    }
    boolean workflowDefinitionIdentityIsInvalid = EmptyChecks.isNull(workflowDefinitionId)
        || !workflowDefinitionId.equals(run.getWorkflowDefinitionId())
        || EmptyChecks.isNull(workflowVersion)
        || !Objects.equals(run.getWorkflowVersionSnapshot(), workflowVersion);
    if (workflowDefinitionIdentityIsInvalid) {
      throw invalidSnapshot();
    }
    boolean runTenantIsInvalid =
        EmptyChecks.isNull(run.getTenantId()) || EmptyChecks.isBlank(run.getTenantId());
    if (runTenantIsInvalid) {
      throw invalidSnapshot();
    }
    if (EmptyChecks.isEmpty(nodes)) {
      throw invalidSnapshot();
    }
    boolean nodeCodeIsMissing = nodes.stream()
        .anyMatch(node ->
            EmptyChecks.isNull(node.getNodeCode()) || EmptyChecks.isBlank(node.getNodeCode()));
    if (nodeCodeIsMissing) {
      throw invalidSnapshot();
    }
    boolean nodeCodesAreDuplicated =
        nodes.stream().map(WorkflowNodeEntity::getNodeCode).distinct().count() != nodes.size();
    if (nodeCodesAreDuplicated) {
      throw invalidSnapshot();
    }
    FrozenDefinitions definitions = FrozenDefinitions.builder()
        .jobs(jobs)
        .pipelines(pipelines)
        .jobMetadata(jobMetadata)
        .replayOperationId(replayOperationId)
        .replayExecutionGuards(replayExecutionGuards)
        .frozenFileTemplateSnapshots(frozenFileTemplateSnapshots)
        .frozenDispatchChannelSnapshots(frozenDispatchChannelSnapshots)
        .dispatchSecretBindings(dispatchSecretBindings)
        .crossDayDependencyResultVersionIdsByNode(crossDayDependencyResultVersionIdsByNode)
        .build();
    validateFrozenDefinitions(run, nodes, edges, definitions);
    Map<String, JobDefinitionEntity> immutableJobs = Map.copyOf(jobs);
    Map<String, SystemPipelineIdentity> immutablePipelines = Map.copyOf(pipelines);
    Map<String, ReplayExecutionGuard> immutableReplayExecutionGuards =
        Map.copyOf(replayExecutionGuards);
    Map<String, Map<String, String>> immutableFileTemplateSnapshots =
        freezeTemplateSnapshots(frozenFileTemplateSnapshots);
    Map<String, Map<String, String>> immutableDispatchChannelSnapshots =
        freezeTemplateSnapshots(frozenDispatchChannelSnapshots);
    Map<String, Map<String, Object>> immutableDispatchSecretBindings =
        freezeSecretBindings(dispatchSecretBindings);
    Map<String, Map<String, List<Long>>> immutableCrossDayDependencyResultVersionIdsByNode =
        freezeCrossDayDependencyResultVersionIds(crossDayDependencyResultVersionIdsByNode);
    return new WorkflowRunExecutionSnapshot(
        schemaVersion,
        workflowDefinitionId,
        workflowVersion,
        workflowCalendarCode,
        replaySessionId,
        replayOperationId,
        nodes,
        edges,
        immutableJobs,
        immutablePipelines,
        immutableReplayExecutionGuards,
        immutableFileTemplateSnapshots,
        immutableDispatchChannelSnapshots,
        immutableDispatchSecretBindings,
        immutableCrossDayDependencyResultVersionIdsByNode);
  }

  /** 同一次冻结定义核对共享的元数据，不重新加载当前配置。 */
  @Builder
  private record FrozenDefinitions(
      Map<String, JobDefinitionEntity> jobs,
      Map<String, SystemPipelineIdentity> pipelines,
      Map<String, Map<?, ?>> jobMetadata,
      Long replayOperationId,
      Map<String, ReplayExecutionGuard> replayExecutionGuards,
      Map<String, Map<String, String>> frozenFileTemplateSnapshots,
      Map<String, Map<String, String>> frozenDispatchChannelSnapshots,
      Map<String, Map<String, Object>> dispatchSecretBindings,
      Map<String, Map<String, List<Long>>> crossDayDependencyResultVersionIdsByNode) {}

  private static void validateFrozenDefinitions(
      WorkflowRunEntity run,
      List<WorkflowNodeEntity> nodes,
      List<WorkflowEdgeEntity> edges,
      FrozenDefinitions definitions) {
    Map<String, WorkflowNodeEntity> nodesByCode = new LinkedHashMap<>();
    for (WorkflowNodeEntity node : nodes) {
      validateNodeOwnership(run, node);
      nodesByCode.put(node.getNodeCode(), node);
      validatePinnedDependencies(node, definitions);
      if (!WorkflowNodeType.JOB.code().equals(node.getNodeType())) {
        continue;
      }
      validateJobSnapshot(run, node, definitions);
    }
    validateSnapshotNodeReferences(nodesByCode, definitions);
    validateEdges(run, edges, nodesByCode);
  }

  private static void validateNodeOwnership(WorkflowRunEntity run, WorkflowNodeEntity node) {
    if (!run.getWorkflowDefinitionId().equals(node.getWorkflowDefinitionId())
        || !run.getTenantId().equals(node.getTenantId())) {
      throw invalidSnapshot();
    }
  }

  private static void validatePinnedDependencies(
      WorkflowNodeEntity node, FrozenDefinitions definitions) {
    Map<String, List<Long>> pinnedDependencyIds =
        definitions.crossDayDependencyResultVersionIdsByNode().get(node.getNodeCode());
    Set<String> declaredDependencyAliases = crossDayDependencyAliases(node);
    boolean declaresCrossDayDependencies =
        !EmptyChecks.isEmpty(Objects.requireNonNull(declaredDependencyAliases));
    boolean replayRequiresPinnedDependencyIds =
        EmptyChecks.isNotNull(definitions.replayOperationId())
            && WorkflowNodeType.JOB.code().equals(node.getNodeType())
            && !Boolean.FALSE.equals(node.getEnabled())
            && declaresCrossDayDependencies
            && EmptyChecks.isEmpty(pinnedDependencyIds);
    if (replayRequiresPinnedDependencyIds) {
      throw invalidSnapshot();
    }
    if (EmptyChecks.isNotNull(pinnedDependencyIds)) {
      boolean pinnedDependenciesAreInvalidForNode =
          EmptyChecks.isNull(definitions.replayOperationId())
              || !WorkflowNodeType.JOB.code().equals(node.getNodeType())
              || Boolean.FALSE.equals(node.getEnabled())
              || !declaresCrossDayDependencies
              || EmptyChecks.isEmpty(Objects.requireNonNull(pinnedDependencyIds));
      if (pinnedDependenciesAreInvalidForNode) {
        throw invalidSnapshot();
      }
      validatePinnedDependencyIds(pinnedDependencyIds, declaredDependencyAliases);
    }
  }

  private static void validateJobSnapshot(
      WorkflowRunEntity run, WorkflowNodeEntity node, FrozenDefinitions definitions) {
    ReplayExecutionGuard replayGuard = definitions.replayExecutionGuards().get(node.getNodeCode());
    boolean enabledReplayNodeIsMissingMatchingGuard =
        EmptyChecks.isNotNull(definitions.replayOperationId())
            && !Boolean.FALSE.equals(node.getEnabled())
            && (EmptyChecks.isNull(replayGuard)
                || !definitions.replayOperationId().equals(replayGuard.replayOperationId()));
    if (enabledReplayNodeIsMissingMatchingGuard) {
      throw invalidSnapshot();
    }
    if (EmptyChecks.isNotNull(replayGuard)
        && !Objects.equals(definitions.replayOperationId(), replayGuard.replayOperationId())) {
      throw invalidSnapshot();
    }
    JobDefinitionEntity job = definitions.jobs().get(node.getNodeCode());
    Map<?, ?> metadata = definitions.jobMetadata().get(node.getNodeCode());
    boolean jobSnapshotIdentityIsInvalid = EmptyChecks.isNull(job)
        || EmptyChecks.isNull(metadata)
        || EmptyChecks.isNull(job.id())
        || !Objects.equals(job.jobCode(), node.getRelatedJobCode())
        || !Objects.equals(job.tenantId(), run.getTenantId());
    if (jobSnapshotIdentityIsInvalid) {
      throw invalidSnapshot();
    }
    Long jobDefinitionId = job.id();
    Long snapshotJobDefinitionId = longNumber(metadata.get("jobDefinitionId"));
    if (!Objects.equals(jobDefinitionId, snapshotJobDefinitionId)) {
      throw invalidSnapshot();
    }
    if (!Objects.equals(job.jobCode(), metadata.get("jobCode"))) {
      throw invalidSnapshot();
    }
    if (!Objects.equals(job.jobType(), metadata.get("jobType"))) {
      throw invalidSnapshot();
    }
    Integer jobDefinitionVersion = job.version();
    Integer snapshotJobDefinitionVersion = number(metadata.get("jobDefinitionVersion"));
    if (!Objects.equals(jobDefinitionVersion, snapshotJobDefinitionVersion)) {
      throw invalidSnapshot();
    }
    validatePipelineIdentity(job, node, metadata, definitions);
    validateReplayInputSnapshots(job, node, definitions);
  }

  private static void validatePipelineIdentity(
      JobDefinitionEntity job,
      WorkflowNodeEntity node,
      Map<?, ?> metadata,
      FrozenDefinitions definitions) {
    Optional<SystemPipelineDefinition> expectedPipeline =
        SystemPipelineCatalog.forJobType(job.jobType());
    SystemPipelineIdentity actualPipeline = definitions.pipelines().get(node.getNodeCode());
    boolean hasPipelineCode = EmptyChecks.isNotNull(metadata.get("systemPipelineCode"));
    boolean hasPipelineVersion = EmptyChecks.isNotNull(metadata.get("systemPipelineVersion"));
    if (hasPipelineCode != hasPipelineVersion) {
      throw invalidSnapshot();
    }
    if (expectedPipeline.isPresent()) {
      SystemPipelineDefinition expected = expectedPipeline.orElseThrow();
      boolean pipelineSnapshotVersionDoesNotMatch = EmptyChecks.isNull(actualPipeline)
          || !expected.code().equals(actualPipeline.code())
          || !Objects.equals(expected.semanticVersion(), actualPipeline.version());
      if (pipelineSnapshotVersionDoesNotMatch) {
        throw invalidSnapshot();
      }
    } else if (EmptyChecks.isNotNull(actualPipeline)) {
      throw invalidSnapshot();
    }
  }

  private static void validateReplayInputSnapshots(
      JobDefinitionEntity job, WorkflowNodeEntity node, FrozenDefinitions definitions) {
    Map<String, String> templateSnapshots =
        definitions.frozenFileTemplateSnapshots().get(node.getNodeCode());
    Map<String, String> channelSnapshots =
        definitions.frozenDispatchChannelSnapshots().get(node.getNodeCode());
    Map<String, Object> secretBindings =
        definitions.dispatchSecretBindings().get(node.getNodeCode());
    boolean fileTemplateJob = isFileTemplateJob(job.jobType());
    boolean dispatchJob = JobType.DISPATCH.code().equals(job.jobType());
    boolean replayFileTemplateSnapshotIsMissing =
        EmptyChecks.isNotNull(definitions.replayOperationId())
            && !Boolean.FALSE.equals(node.getEnabled())
            && fileTemplateJob
            && EmptyChecks.isEmpty(templateSnapshots);
    if (replayFileTemplateSnapshotIsMissing) {
      throw invalidSnapshot();
    }
    if (!fileTemplateJob && EmptyChecks.isNotEmpty(templateSnapshots)) {
      throw invalidSnapshot();
    }
    boolean replayDispatchChannelSnapshotIsMissing =
        EmptyChecks.isNotNull(definitions.replayOperationId())
            && !Boolean.FALSE.equals(node.getEnabled())
            && dispatchJob
            && EmptyChecks.isEmpty(channelSnapshots);
    if (replayDispatchChannelSnapshotIsMissing) {
      throw invalidSnapshot();
    }
    boolean nonDispatchJobHasChannelSnapshots = !dispatchJob
        && (EmptyChecks.isNotEmpty(channelSnapshots) || EmptyChecks.isNotEmpty(secretBindings));
    if (nonDispatchJobHasChannelSnapshots) {
      throw invalidSnapshot();
    }
    boolean secretBindingsReferenceMissingChannels = EmptyChecks.isNotEmpty(secretBindings)
        && (EmptyChecks.isEmpty(channelSnapshots)
            || !channelSnapshots.keySet().containsAll(secretBindings.keySet()));
    if (secretBindingsReferenceMissingChannels) {
      throw invalidSnapshot();
    }
  }

  private static void validateSnapshotNodeReferences(
      Map<String, WorkflowNodeEntity> nodesByCode, FrozenDefinitions definitions) {
    for (Map.Entry<String, JobDefinitionEntity> entry : definitions.jobs().entrySet()) {
      WorkflowNodeEntity node = nodesByCode.get(entry.getKey());
      if (EmptyChecks.isNull(node) || !WorkflowNodeType.JOB.code().equals(node.getNodeType())) {
        throw invalidSnapshot();
      }
    }
    for (String nodeCode : definitions.jobMetadata().keySet()) {
      WorkflowNodeEntity node = nodesByCode.get(nodeCode);
      if (EmptyChecks.isNull(node) || !WorkflowNodeType.JOB.code().equals(node.getNodeType())) {
        throw invalidSnapshot();
      }
    }
    for (String nodeCode : definitions.replayExecutionGuards().keySet()) {
      WorkflowNodeEntity node = nodesByCode.get(nodeCode);
      if (EmptyChecks.isNull(node) || !WorkflowNodeType.JOB.code().equals(node.getNodeType())) {
        throw invalidSnapshot();
      }
    }
    for (String nodeCode : definitions.frozenFileTemplateSnapshots().keySet()) {
      WorkflowNodeEntity node = nodesByCode.get(nodeCode);
      boolean nodeCannotProvideReplayJob = EmptyChecks.isNull(node)
          || !WorkflowNodeType.JOB.code().equals(node.getNodeType())
          || EmptyChecks.isNull(definitions.replayOperationId());
      if (nodeCannotProvideReplayJob) {
        throw invalidSnapshot();
      }
    }
    for (String nodeCode : definitions.frozenDispatchChannelSnapshots().keySet()) {
      WorkflowNodeEntity node = nodesByCode.get(nodeCode);
      JobDefinitionEntity job = definitions.jobs().get(nodeCode);
      boolean dispatchReplayContextIsInvalid = EmptyChecks.isNull(node)
          || !WorkflowNodeType.JOB.code().equals(node.getNodeType())
          || EmptyChecks.isNull(definitions.replayOperationId())
          || EmptyChecks.isNull(job)
          || !JobType.DISPATCH.code().equals(job.jobType());
      if (dispatchReplayContextIsInvalid) {
        throw invalidSnapshot();
      }
    }
    for (String nodeCode : definitions.dispatchSecretBindings().keySet()) {
      WorkflowNodeEntity node = nodesByCode.get(nodeCode);
      JobDefinitionEntity job = definitions.jobs().get(nodeCode);
      boolean dispatchReplayContextIsInvalid = EmptyChecks.isNull(node)
          || !WorkflowNodeType.JOB.code().equals(node.getNodeType())
          || EmptyChecks.isNull(definitions.replayOperationId())
          || EmptyChecks.isNull(job)
          || !JobType.DISPATCH.code().equals(job.jobType());
      if (dispatchReplayContextIsInvalid) {
        throw invalidSnapshot();
      }
    }
    for (String nodeCode :
        definitions.crossDayDependencyResultVersionIdsByNode().keySet()) {
      WorkflowNodeEntity node = nodesByCode.get(nodeCode);
      boolean nodeCannotProvideReplayJob = EmptyChecks.isNull(node)
          || !WorkflowNodeType.JOB.code().equals(node.getNodeType())
          || EmptyChecks.isNull(definitions.replayOperationId());
      if (nodeCannotProvideReplayJob) {
        throw invalidSnapshot();
      }
    }
  }

  private static void validateEdges(
      WorkflowRunEntity run,
      List<WorkflowEdgeEntity> edges,
      Map<String, WorkflowNodeEntity> nodesByCode) {
    for (WorkflowEdgeEntity edge : edges) {
      if (Boolean.FALSE.equals(edge.getEnabled())) {
        continue;
      }
      boolean enabledEdgeIdentityIsInvalid =
          !run.getWorkflowDefinitionId().equals(edge.getWorkflowDefinitionId())
              || !run.getTenantId().equals(edge.getTenantId())
              || EmptyChecks.isNull(edge.getFromNodeCode())
              || EmptyChecks.isBlank(edge.getFromNodeCode())
              || EmptyChecks.isNull(edge.getToNodeCode())
              || EmptyChecks.isBlank(edge.getToNodeCode());
      if (enabledEdgeIdentityIsInvalid) {
        throw invalidSnapshot();
      }
      if (!isBoundaryCode(edge.getFromNodeCode())
          && !nodesByCode.containsKey(edge.getFromNodeCode())) {
        throw invalidSnapshot();
      }
      if (!isBoundaryCode(edge.getToNodeCode()) && !nodesByCode.containsKey(edge.getToNodeCode())) {
        throw invalidSnapshot();
      }
    }
  }

  private static boolean isBoundaryCode(String nodeCode) {
    return WorkflowNodeType.START.code().equals(nodeCode)
        || WorkflowNodeType.END.code().equals(nodeCode);
  }

  private static Set<String> crossDayDependencyAliases(WorkflowNodeEntity node) {
    String declarations = node.getCrossDayDependencies();
    if (EmptyChecks.isBlank(declarations)) {
      return Set.of();
    }
    try {
      List<CrossDayDependencySpec> specs =
          JsonUtils.fromJson(declarations, new TypeReference<List<CrossDayDependencySpec>>() {});
      if (EmptyChecks.isEmpty(specs)) {
        return Set.of();
      }
      Set<String> aliases = new LinkedHashSet<>();
      for (CrossDayDependencySpec spec : specs) {
        boolean replaySnapshotSpecIsInvalid = EmptyChecks.isNull(spec)
            || EmptyChecks.isBlank(spec.jobCode())
            || !spec.hasSupportedStrategy()
            || !spec.hasValidVersionSelector();
        if (replaySnapshotSpecIsInvalid) {
          throw invalidSnapshot();
        }
        String alias = resolvedAlias(spec);
        if (!aliases.add(alias)) {
          throw invalidSnapshot();
        }
      }
      return Set.copyOf(aliases);
    } catch (BizException invalidSnapshot) {
      throw invalidSnapshot;
    } catch (RuntimeException invalidDeclarations) {
      throw invalidSnapshot();
    }
  }

  private static String resolvedAlias(CrossDayDependencySpec spec) {
    if (EmptyChecks.isNotBlank(spec.alias())) {
      return spec.alias();
    }
    if (spec.isOffsetMode()) {
      int offset = spec.bizDateOffset();
      String sign = offset < 0 ? "minus_" : "plus_";
      return "t_" + sign + Math.abs(offset);
    }
    if (EmptyChecks.isNotBlank(spec.bizDateRange())) {
      return spec.bizDateRange().toLowerCase(Locale.ROOT);
    }
    return spec.jobCode().toLowerCase(Locale.ROOT);
  }

  private static void validatePinnedDependencyIds(
      Map<String, List<Long>> idsByAlias, Set<String> declaredAliases) {
    if (!declaredAliases.equals(idsByAlias.keySet())) {
      throw invalidSnapshot();
    }
    for (Map.Entry<String, List<Long>> entry : idsByAlias.entrySet()) {
      if (EmptyChecks.isBlank(entry.getKey()) || EmptyChecks.isNull(entry.getValue())) {
        throw invalidSnapshot();
      }
      if (entry.getValue().stream().anyMatch(id -> EmptyChecks.isNull(id) || id <= 0)
          || entry.getValue().stream().distinct().count() != entry.getValue().size()) {
        throw invalidSnapshot();
      }
    }
  }

  private static BizException invalidSnapshot() {
    return BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
  }

  public WorkflowNodeEntity node(String nodeCode) {
    if (EmptyChecks.isNull(nodes) || EmptyChecks.isNull(nodeCode)) {
      return null;
    }
    return nodes.stream()
        .filter(node -> nodeCode.equals(node.getNodeCode()))
        .findFirst()
        .orElse(null);
  }

  public JobDefinitionEntity jobDefinition(String nodeCode) {
    return EmptyChecks.isNull(jobDefinitions) ? null : jobDefinitions.get(nodeCode);
  }

  public ReplayExecutionGuard replayExecutionGuard(String nodeCode) {
    return EmptyChecks.isNull(replayExecutionGuards) ? null : replayExecutionGuards.get(nodeCode);
  }

  public Map<String, String> frozenFileTemplateSnapshots(String nodeCode) {
    return EmptyChecks.isNull(frozenFileTemplateSnapshots)
        ? Map.of()
        : frozenFileTemplateSnapshots.getOrDefault(nodeCode, Map.of());
  }

  public Map<String, String> frozenDispatchChannelSnapshots(String nodeCode) {
    return EmptyChecks.isNull(frozenDispatchChannelSnapshots)
        ? Map.of()
        : frozenDispatchChannelSnapshots.getOrDefault(nodeCode, Map.of());
  }

  public Map<String, Object> dispatchSecretBindings(String nodeCode) {
    return EmptyChecks.isNull(dispatchSecretBindings)
        ? Map.of()
        : dispatchSecretBindings.getOrDefault(nodeCode, Map.of());
  }

  public Map<String, List<Long>> crossDayDependencyResultVersionIds(String nodeCode) {
    return EmptyChecks.isNull(crossDayDependencyResultVersionIdsByNode)
        ? Map.of()
        : crossDayDependencyResultVersionIdsByNode.getOrDefault(nodeCode, Map.of());
  }

  public void copySystemPipelineIdentity(String nodeCode, Map<String, Object> target) {
    SystemPipelineIdentity identity = systemPipeline(nodeCode);
    if (EmptyChecks.isNotNull(identity) && EmptyChecks.isNotNull(target)) {
      target.put(SystemPipelineTaskKeys.CODE, identity.code());
      target.put(SystemPipelineTaskKeys.VERSION, identity.version());
    }
  }

  public SystemPipelineIdentity systemPipeline(String nodeCode) {
    return EmptyChecks.isNull(systemPipelines) ? null : systemPipelines.get(nodeCode);
  }

  public List<WorkflowEdgeEntity> outgoing(String nodeCode) {
    return EmptyChecks.isNull(edges)
        ? List.of()
        : edges.stream()
            .filter(edge -> Boolean.TRUE.equals(edge.getEnabled()))
            .filter(edge -> Objects.equals(nodeCode, edge.getFromNodeCode()))
            .toList();
  }

  public List<WorkflowEdgeEntity> incoming(String nodeCode) {
    return EmptyChecks.isNull(edges)
        ? List.of()
        : edges.stream()
            .filter(edge -> Boolean.TRUE.equals(edge.getEnabled()))
            .filter(edge -> Objects.equals(nodeCode, edge.getToNodeCode()))
            .toList();
  }

  private static <T> List<T> readList(Object value, Class<T> type) {
    if (EmptyChecks.isNull(value)) {
      return List.of();
    }
    ObjectMapper valueMapper = JsonUtils.newDefaultMapper();
    ObjectMapper typeFactoryMapper = JsonUtils.newDefaultMapper();
    JavaType listType =
        typeFactoryMapper.getTypeFactory().constructCollectionType(List.class, type);
    return valueMapper.convertValue(value, listType);
  }

  private static ReplayExecutionGuard readReplayExecutionGuard(Object value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    try {
      String serializedGuard = JsonUtils.toJson(value);
      ReplayExecutionGuard replayGuard =
          JsonUtils.fromJson(serializedGuard, ReplayExecutionGuard.class);
      return replayGuard;
    } catch (RuntimeException invalidGuard) {
      throw invalidSnapshot();
    }
  }

  private static Map<String, String> readProtectedTemplateSnapshots(Object value) {
    if (!(value instanceof Map<?, ?> entries) || EmptyChecks.isEmpty(entries)) {
      throw invalidSnapshot();
    }
    Map<String, String> snapshots = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : entries.entrySet()) {
      if (!(entry.getKey() instanceof String templateCode)
          || EmptyChecks.isBlank(templateCode)
          || !(entry.getValue() instanceof String protectedSnapshot)
          || EmptyChecks.isBlank(protectedSnapshot)) {
        throw invalidSnapshot();
      }
      snapshots.put(templateCode, protectedSnapshot);
    }
    return snapshots;
  }

  private static Map<String, String> readProtectedDispatchChannelSnapshots(Object value) {
    return readProtectedTemplateSnapshots(value);
  }

  private static Map<String, Object> readDispatchSecretBindings(Object value) {
    if (!(value instanceof Map<?, ?> entries)) {
      throw invalidSnapshot();
    }
    Map<String, Object> bindings = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : entries.entrySet()) {
      if (!(entry.getKey() instanceof String channelCode)
          || EmptyChecks.isBlank(channelCode)
          || !(entry.getValue() instanceof Map<?, ?> binding)) {
        throw invalidSnapshot();
      }
      Map<String, Object> namedBinding = new LinkedHashMap<>();
      for (Map.Entry<?, ?> bindingEntry : binding.entrySet()) {
        if (!(bindingEntry.getKey() instanceof String key) || EmptyChecks.isBlank(key)) {
          throw invalidSnapshot();
        }
        namedBinding.put(key, bindingEntry.getValue());
      }
      bindings.put(channelCode, immutableMap(namedBinding));
    }
    return Collections.unmodifiableMap(bindings);
  }

  private static Map<String, Map<String, List<Long>>> readCrossDayDependencyResultVersionIds(
      Object value) {
    if (EmptyChecks.isNull(value)) {
      return new LinkedHashMap<>();
    }
    try {
      String serializedIds = JsonUtils.toJson(value);
      Map<String, Map<String, List<Long>>> idsByNode =
          JsonUtils.fromJson(serializedIds, new TypeReference<>() {});
      if (EmptyChecks.isNull(idsByNode)
          || idsByNode.entrySet().stream()
              .anyMatch(entry ->
                  EmptyChecks.isBlank(entry.getKey()) || EmptyChecks.isNull(entry.getValue()))) {
        throw invalidSnapshot();
      }
      return new LinkedHashMap<>(idsByNode);
    } catch (BizException invalidSnapshot) {
      throw invalidSnapshot;
    } catch (RuntimeException invalidIds) {
      throw invalidSnapshot();
    }
  }

  private static Map<String, Map<String, String>> freezeTemplateSnapshots(
      Map<String, Map<String, String>> snapshots) {
    Map<String, Map<String, String>> immutable = new LinkedHashMap<>();
    snapshots.forEach((nodeCode, templates) -> immutable.put(nodeCode, Map.copyOf(templates)));
    return Map.copyOf(immutable);
  }

  private static Map<String, Map<String, Object>> freezeSecretBindings(
      Map<String, Map<String, Object>> bindings) {
    Map<String, Map<String, Object>> immutable = new LinkedHashMap<>();
    bindings.forEach(
        (nodeCode, channelBindings) -> immutable.put(nodeCode, immutableMap(channelBindings)));
    return Map.copyOf(immutable);
  }

  private static Map<String, Map<String, List<Long>>> freezeCrossDayDependencyResultVersionIds(
      Map<String, Map<String, List<Long>>> idsByNode) {
    Map<String, Map<String, List<Long>>> immutable = new LinkedHashMap<>();
    idsByNode.forEach((nodeCode, idsByAlias) -> {
      Map<String, List<Long>> frozenIdsByAlias = new LinkedHashMap<>();
      idsByAlias.forEach((alias, ids) -> frozenIdsByAlias.put(alias, List.copyOf(ids)));
      immutable.put(nodeCode, Map.copyOf(frozenIdsByAlias));
    });
    return Map.copyOf(immutable);
  }

  private static Map<String, Object> immutableMap(Map<String, Object> source) {
    Map<String, Object> immutable = new LinkedHashMap<>();
    source.forEach((key, value) -> immutable.put(key, immutableValue(value)));
    return Collections.unmodifiableMap(immutable);
  }

  private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?> rawMap) {
      Map<String, Object> namedValues = new LinkedHashMap<>();
      rawMap.forEach((key, item) -> {
        if (!(key instanceof String text)) {
          throw invalidSnapshot();
        }
        namedValues.put(text, immutableValue(item));
      });
      return Collections.unmodifiableMap(namedValues);
    }
    if (value instanceof List<?> items) {
      return items.stream().map(WorkflowRunExecutionSnapshot::immutableValue).toList();
    }
    return value;
  }

  private static boolean isFileTemplateJob(String jobType) {
    return "IMPORT".equals(jobType)
        || "EXPORT".equals(jobType)
        || "BUNDLE_IMPORT".equals(jobType)
        || "BUNDLE_EXPORT".equals(jobType);
  }

  private static Integer number(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    return null;
  }

  private static Long longNumber(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return null;
  }

  private static String string(Object value) {
    return value instanceof String text && EmptyChecks.isNotBlank(text) ? text : null;
  }
}
