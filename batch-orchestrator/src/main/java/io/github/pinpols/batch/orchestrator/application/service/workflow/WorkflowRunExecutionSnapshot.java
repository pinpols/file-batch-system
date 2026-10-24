package io.github.pinpols.batch.orchestrator.application.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable definition inputs captured when a Workflow Run is created. */
public record WorkflowRunExecutionSnapshot(
    Integer schemaVersion,
    Long workflowDefinitionId,
    Integer workflowVersion,
    String workflowCalendarCode,
    Long replaySessionId,
    List<WorkflowNodeEntity> nodes,
    List<WorkflowEdgeEntity> edges,
    Map<String, JobDefinitionEntity> jobDefinitions,
    Map<String, SystemPipelineIdentity> systemPipelines) {

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
    // Schema v1 only captured launch identity; it did not freeze the graph. Keep legacy runs on
    // the legacy definition-read path rather than treating an empty v1 graph as authoritative.
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
    Map<String, Map<?, ?>> jobMetadata = new LinkedHashMap<>();
    Object rawJobs = raw.get("jobDefinitions");
    if (rawJobs instanceof Map<?, ?> entries) {
      entries.forEach((key, value) -> {
        if (key instanceof String nodeCode && value instanceof Map<?, ?> jobEntry) {
          jobMetadata.put(nodeCode, jobEntry);
          Object definition = jobEntry.get("executionDefinition");
          if (EmptyChecks.isNotNull(definition)) {
            JobDefinitionEntity job =
                JsonUtils.fromJson(JsonUtils.toJson(definition), JobDefinitionEntity.class);
            if (EmptyChecks.isNotNull(job)) {
              jobs.put(nodeCode, job);
            }
          }
          String pipelineCode = string(jobEntry.get("systemPipelineCode"));
          Integer pipelineVersion = number(jobEntry.get("systemPipelineVersion"));
          if (EmptyChecks.isNotNull(pipelineCode) && EmptyChecks.isNotNull(pipelineVersion)) {
            pipelines.put(nodeCode, new SystemPipelineIdentity(pipelineCode, pipelineVersion));
          }
        }
      });
    }
    Long workflowDefinitionId = longNumber(raw.get("workflowDefinitionId"));
    Integer workflowVersion = number(raw.get("workflowVersion"));
    String workflowCalendarCode = string(raw.get("workflowCalendarCode"));
    Long replaySessionId = longNumber(raw.get("replaySessionId"));
    if (EmptyChecks.isNull(workflowDefinitionId)
        || !workflowDefinitionId.equals(run.getWorkflowDefinitionId())
        || EmptyChecks.isNull(workflowVersion)
        || !Objects.equals(run.getWorkflowVersionSnapshot(), workflowVersion)
        || EmptyChecks.isNull(run.getTenantId())
        || EmptyChecks.isBlank(run.getTenantId())
        || EmptyChecks.isEmpty(nodes)
        || nodes.stream()
            .anyMatch(node ->
                EmptyChecks.isNull(node.getNodeCode()) || EmptyChecks.isBlank(node.getNodeCode()))
        || nodes.stream().map(WorkflowNodeEntity::getNodeCode).distinct().count() != nodes.size()) {
      throw invalidSnapshot();
    }
    validateFrozenDefinitions(run, nodes, edges, jobs, pipelines, jobMetadata);
    return new WorkflowRunExecutionSnapshot(
        schemaVersion,
        workflowDefinitionId,
        workflowVersion,
        workflowCalendarCode,
        replaySessionId,
        nodes,
        edges,
        Map.copyOf(jobs),
        Map.copyOf(pipelines));
  }

  private static void validateFrozenDefinitions(
      WorkflowRunEntity run,
      List<WorkflowNodeEntity> nodes,
      List<WorkflowEdgeEntity> edges,
      Map<String, JobDefinitionEntity> jobs,
      Map<String, SystemPipelineIdentity> pipelines,
      Map<String, Map<?, ?>> jobMetadata) {
    Map<String, WorkflowNodeEntity> nodesByCode = new LinkedHashMap<>();
    for (WorkflowNodeEntity node : nodes) {
      if (!run.getWorkflowDefinitionId().equals(node.getWorkflowDefinitionId())
          || !run.getTenantId().equals(node.getTenantId())) {
        throw invalidSnapshot();
      }
      nodesByCode.put(node.getNodeCode(), node);
      if (!WorkflowNodeType.JOB.code().equals(node.getNodeType())) {
        continue;
      }
      JobDefinitionEntity job = jobs.get(node.getNodeCode());
      Map<?, ?> metadata = jobMetadata.get(node.getNodeCode());
      if (EmptyChecks.isNull(job)
          || EmptyChecks.isNull(metadata)
          || EmptyChecks.isNull(job.id())
          || !Objects.equals(job.jobCode(), node.getRelatedJobCode())
          || !Objects.equals(job.tenantId(), run.getTenantId())
          || !Objects.equals(job.id(), longNumber(metadata.get("jobDefinitionId")))
          || !Objects.equals(job.jobCode(), metadata.get("jobCode"))
          || !Objects.equals(job.jobType(), metadata.get("jobType"))
          || !Objects.equals(job.version(), number(metadata.get("jobDefinitionVersion")))) {
        throw invalidSnapshot();
      }
      Optional<SystemPipelineDefinition> expectedPipeline =
          SystemPipelineCatalog.forJobType(job.jobType());
      SystemPipelineIdentity actualPipeline = pipelines.get(node.getNodeCode());
      boolean hasPipelineCode = EmptyChecks.isNotNull(metadata.get("systemPipelineCode"));
      boolean hasPipelineVersion = EmptyChecks.isNotNull(metadata.get("systemPipelineVersion"));
      if (hasPipelineCode != hasPipelineVersion) {
        throw invalidSnapshot();
      }
      if (expectedPipeline.isPresent()) {
        SystemPipelineDefinition expected = expectedPipeline.orElseThrow();
        if (EmptyChecks.isNull(actualPipeline)
            || !expected.code().equals(actualPipeline.code())
            || !Objects.equals(expected.semanticVersion(), actualPipeline.version())) {
          throw invalidSnapshot();
        }
      } else if (EmptyChecks.isNotNull(actualPipeline)) {
        throw invalidSnapshot();
      }
    }
    for (Map.Entry<String, JobDefinitionEntity> entry : jobs.entrySet()) {
      WorkflowNodeEntity node = nodesByCode.get(entry.getKey());
      if (EmptyChecks.isNull(node) || !WorkflowNodeType.JOB.code().equals(node.getNodeType())) {
        throw invalidSnapshot();
      }
    }
    for (String nodeCode : jobMetadata.keySet()) {
      WorkflowNodeEntity node = nodesByCode.get(nodeCode);
      if (EmptyChecks.isNull(node) || !WorkflowNodeType.JOB.code().equals(node.getNodeType())) {
        throw invalidSnapshot();
      }
    }
    for (WorkflowEdgeEntity edge : edges) {
      if (Boolean.FALSE.equals(edge.getEnabled())) {
        continue;
      }
      if (!run.getWorkflowDefinitionId().equals(edge.getWorkflowDefinitionId())
          || !run.getTenantId().equals(edge.getTenantId())
          || EmptyChecks.isNull(edge.getFromNodeCode())
          || EmptyChecks.isBlank(edge.getFromNodeCode())
          || EmptyChecks.isNull(edge.getToNodeCode())
          || EmptyChecks.isBlank(edge.getToNodeCode())) {
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
    return JsonUtils.newDefaultMapper()
        .convertValue(
            value,
            JsonUtils.newDefaultMapper()
                .getTypeFactory()
                .constructCollectionType(List.class, type));
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
