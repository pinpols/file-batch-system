package io.github.pinpols.batch.console.domain.workflow.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowExecutionProgressResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowExecutionProgressResponse.Edge;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowExecutionProgressResponse.NodeProgress;
import io.github.pinpols.batch.console.domain.workflow.view.WorkflowExecutionNodeView;
import io.github.pinpols.batch.console.domain.workflow.view.WorkflowExecutionSnapshotView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 只从本次运行的冻结节点/边构建图，禁止退回可变定义图。 */
public final class WorkflowExecutionGraphAssembler {
  private WorkflowExecutionGraphAssembler() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Snapshot(
      Integer schemaVersion,
      Long workflowDefinitionId,
      Integer workflowVersion,
      List<FrozenNode> nodes,
      List<FrozenEdge> edges) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FrozenNode(
      String nodeCode, String nodeName, String nodeType, String relatedJobCode, Boolean enabled) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FrozenEdge(
      String fromNodeCode, String toNodeCode, String edgeType, Boolean enabled) {}

  public static WorkflowExecutionProgressResponse assemble(
      WorkflowExecutionSnapshotView run, List<WorkflowExecutionNodeView> runtime) {
    WorkflowExecutionProgressResponse.WorkflowExecutionProgressResponseBuilder response =
        WorkflowExecutionProgressResponse.builder()
            .workflowRunId(run.getId())
            .tenantId(run.getTenantId())
            .workflowDefinitionId(run.getWorkflowDefinitionId())
            .workflowVersion(run.getWorkflowVersionSnapshot())
            .runStatus(run.getRunStatus())
            .bizDate(run.getBizDate())
            .startedAt(run.getStartedAt())
            .finishedAt(run.getFinishedAt())
            .generatedAt(BatchDateTimeSupport.utcNow());
    String executionSnapshot = run.getExecutionSnapshot();
    if (EmptyChecks.isBlank(executionSnapshot)) {
      return response.snapshotAvailable(false).nodes(List.of()).edges(List.of()).build();
    }
    Snapshot snapshot;
    try {
      snapshot = JsonUtils.fromJsonStrict(executionSnapshot, Snapshot.class);
    } catch (IllegalArgumentException malformed) {
      throw invalidSnapshot();
    }
    if (EmptyChecks.isNull(snapshot)) {
      throw invalidSnapshot();
    }
    List<FrozenNode> frozenNodes = snapshot.nodes();
    if (!Objects.equals(snapshot.schemaVersion(), 2)
        || !Objects.equals(snapshot.workflowDefinitionId(), run.getWorkflowDefinitionId())
        || !Objects.equals(snapshot.workflowVersion(), run.getWorkflowVersionSnapshot())
        || EmptyChecks.isEmpty(frozenNodes)
        || EmptyChecks.isNull(snapshot.edges())) {
      throw invalidSnapshot();
    }
    Map<String, WorkflowExecutionNodeView> latest = new HashMap<>();
    for (WorkflowExecutionNodeView node : runtime) {
      latest.merge(node.getNodeCode(), node, WorkflowExecutionGraphAssembler::newest);
    }
    Set<String> codes = new HashSet<>();
    List<NodeProgress> nodes = new ArrayList<>();
    for (FrozenNode frozen : frozenNodes) {
      if (Boolean.FALSE.equals(frozen.enabled())) continue;
      String nodeCode = frozen.nodeCode();
      if (EmptyChecks.isBlank(nodeCode)
          || EmptyChecks.isNull(frozen.nodeType())
          || !codes.add(nodeCode)) throw invalidSnapshot();
      WorkflowExecutionNodeView current = latest.get(nodeCode);
      NodeProgress.NodeProgressBuilder node = NodeProgress.builder()
          .nodeCode(nodeCode)
          .nodeName(frozen.nodeName())
          .nodeType(frozen.nodeType())
          .relatedJobCode(frozen.relatedJobCode());
      if (EmptyChecks.isNull(current)) {
        nodes.add(node.nodeStatus(
                WorkflowRunStatus.terminalCodes().contains(run.getRunStatus())
                    ? "NOT_EXECUTED"
                    : "PENDING")
            .activeSteps(List.of())
            .createdTasks(0L)
            .finishedTasks(0L)
            .build());
      } else {
        Integer completed = EmptyChecks.isNull(current.getExpectedPartitions())
            ? null
            : zero(current.getSuccessPartitions())
                + zero(current.getFailedPartitions())
                + zero(current.getCancelledPartitions())
                + zero(current.getTerminatedPartitions());
        String activeStepText = current.getActiveSteps();
        List<String> activeSteps = EmptyChecks.isBlank(activeStepText)
            ? List.of()
            : Arrays.asList(activeStepText.split(","));
        nodes.add(node.nodeStatus(current.getNodeStatus())
            .nodeRunId(current.getId())
            .runSeq(current.getRunSeq())
            .jobInstanceId(current.getJobInstanceId())
            .jobInstanceStatus(current.getJobInstanceStatus())
            .expectedPartitions(current.getExpectedPartitions())
            .completedPartitions(completed)
            .createdTasks(current.getCreatedTasks())
            .finishedTasks(current.getFinishedTasks())
            .activeSteps(activeSteps)
            .errorCode(current.getErrorCode())
            .skipReasonCode(current.getSkipReasonCode())
            .startedAt(current.getStartedAt())
            .finishedAt(current.getFinishedAt())
            .build());
      }
    }
    if (!codes.containsAll(latest.keySet())) throw invalidSnapshot();
    List<Edge> edges = new ArrayList<>();
    for (FrozenEdge edge : snapshot.edges()) {
      if (Boolean.FALSE.equals(edge.enabled())) continue;
      if (!codes.contains(edge.fromNodeCode()) || !codes.contains(edge.toNodeCode()))
        throw invalidSnapshot();
      edges.add(new Edge(edge.fromNodeCode(), edge.toNodeCode(), edge.edgeType()));
    }
    return response
        .snapshotAvailable(true)
        .nodes(List.copyOf(nodes))
        .edges(List.copyOf(edges))
        .build();
  }

  private static WorkflowExecutionNodeView newest(
      WorkflowExecutionNodeView left, WorkflowExecutionNodeView right) {
    int sequence = Integer.compare(zero(left.getRunSeq()), zero(right.getRunSeq()));
    if (sequence != 0) return sequence > 0 ? left : right;
    return left.getId() >= right.getId() ? left : right;
  }

  private static int zero(Integer value) {
    return EmptyChecks.isNull(value) ? 0 : value;
  }

  private static BizException invalidSnapshot() {
    return BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.snapshot_invalid");
  }
}
