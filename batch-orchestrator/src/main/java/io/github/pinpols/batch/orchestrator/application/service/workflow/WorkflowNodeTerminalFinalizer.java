package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Finalizes a workflow immediately when a non-worker node completes the whole DAG. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowNodeTerminalFinalizer {

  private static final Set<String> TERMINAL_NODE_STATUSES = Set.of(
      WorkflowNodeRunStatus.SUCCESS.code(),
      WorkflowNodeRunStatus.FAILED.code(),
      WorkflowNodeRunStatus.SKIPPED.code());
  private static final List<String> LIVE_RUN_STATUSES =
      List.of(WorkflowRunStatus.CREATED.code(), WorkflowRunStatus.RUNNING.code());

  private final OrchestratorWorkflowMappers workflowMappers;
  private final WorkflowTerminalOutboxService terminalOutboxService;

  public void finalizeIfAllNodesTerminal(WorkflowRunEntity workflowRun) {
    List<WorkflowNodeRunEntity> allNodeRuns =
        workflowMappers.workflowNodeRunMapper.selectByWorkflowRunId(workflowRun.getId());
    Map<String, WorkflowNodeRunEntity> latestByNode = new HashMap<>();
    for (WorkflowNodeRunEntity nodeRun : allNodeRuns) {
      latestByNode.merge(
          nodeRun.getNodeCode(),
          nodeRun,
          (left, right) -> left.getRunSeq() >= right.getRunSeq() ? left : right);
    }
    List<WorkflowNodeRunEntity> nodeRuns = List.copyOf(latestByNode.values());
    if (EmptyChecks.isEmpty(nodeRuns)
        || nodeRuns.stream()
            .anyMatch(node -> !TERMINAL_NODE_STATUSES.contains(node.getNodeStatus()))) {
      return;
    }
    boolean failed = nodeRuns.stream()
        .anyMatch(node -> WorkflowNodeRunStatus.FAILED.code().equals(node.getNodeStatus()));
    boolean dryRun = Boolean.TRUE.equals(workflowRun.getDryRun());
    String status = failed
        ? (dryRun ? WorkflowRunStatus.FAILED_DRY_RUN.code() : WorkflowRunStatus.FAILED.code())
        : (dryRun ? WorkflowRunStatus.SUCCESS_DRY_RUN.code() : WorkflowRunStatus.SUCCESS.code());
    Instant finishedAt = BatchDateTimeSupport.utcNow();
    int updated =
        workflowMappers.workflowRunMapper.updateStatus(UpdateWorkflowRunStatusParam.builder()
            .tenantId(workflowRun.getTenantId())
            .id(workflowRun.getId())
            .runStatus(status)
            .currentNodeCode(WorkflowNodeCode.END.code())
            .finishedAt(finishedAt)
            .expectedStatuses(LIVE_RUN_STATUSES)
            .build());
    if (updated > 0) {
      terminalOutboxService.writeTerminalEvent(workflowRun, status, finishedAt);
    } else {
      log.debug(
          "workflow node completion finalization lost CAS: workflowRunId={}", workflowRun.getId());
    }
  }
}
