package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 非 Worker 节点完成整个 DAG 时，立即结束 Workflow。 */
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

  @Transactional(propagation = Propagation.MANDATORY)
  public void finalizeIfAllNodesTerminal(WorkflowRunEntity workflowRun) {
    WorkflowRunEntity currentRun = workflowMappers.workflowRunMapper.selectByTenantAndIdForUpdate(
        workflowRun.getTenantId(), workflowRun.getId());
    if (EmptyChecks.isNull(currentRun) || !LIVE_RUN_STATUSES.contains(currentRun.getRunStatus())) {
      return;
    }
    List<WorkflowNodeRunEntity> allNodeRuns =
        workflowMappers.workflowNodeRunMapper.selectByWorkflowRunId(currentRun.getId());
    Map<String, WorkflowNodeRunEntity> latestByNode = new HashMap<>();
    for (WorkflowNodeRunEntity nodeRun : allNodeRuns) {
      latestByNode.merge(
          nodeRun.getNodeCode(),
          nodeRun,
          (left, right) -> left.getRunSeq() >= right.getRunSeq() ? left : right);
    }
    // 尚未派发的并行分支没有 node_run，必须以冻结图确认节点是否齐全。
    WorkflowRunExecutionSnapshot snapshot = WorkflowRunExecutionSnapshot.from(currentRun);
    if (EmptyChecks.isNotNull(snapshot)) {
      boolean missingNode = snapshot.nodes().stream()
          .filter(node -> !Boolean.FALSE.equals(node.getEnabled()))
          // END 是控制边界；跳过全部业务分支时允许它不被激活。
          .filter(node -> !WorkflowNodeType.END.code().equals(node.getNodeType()))
          .anyMatch(node -> !latestByNode.containsKey(node.getNodeCode()));
      if (missingNode) {
        return;
      }
    }
    List<WorkflowNodeRunEntity> nodeRuns = List.copyOf(latestByNode.values());
    if (EmptyChecks.isEmpty(nodeRuns)
        || nodeRuns.stream()
            .anyMatch(node -> !TERMINAL_NODE_STATUSES.contains(node.getNodeStatus()))) {
      return;
    }
    boolean failed = nodeRuns.stream()
        .anyMatch(node -> WorkflowNodeRunStatus.FAILED.code().equals(node.getNodeStatus()));
    boolean dryRun = Boolean.TRUE.equals(currentRun.getDryRun());
    String status = failed
        ? (dryRun ? WorkflowRunStatus.FAILED_DRY_RUN.code() : WorkflowRunStatus.FAILED.code())
        : (dryRun ? WorkflowRunStatus.SUCCESS_DRY_RUN.code() : WorkflowRunStatus.SUCCESS.code());
    Instant finishedAt = BatchDateTimeSupport.utcNow();
    int updated =
        workflowMappers.workflowRunMapper.updateStatus(UpdateWorkflowRunStatusParam.builder()
            .tenantId(currentRun.getTenantId())
            .id(currentRun.getId())
            .runStatus(status)
            .currentNodeCode(WorkflowNodeCode.END.code())
            .finishedAt(finishedAt)
            .expectedStatuses(LIVE_RUN_STATUSES)
            .build());
    if (updated > 0) {
      terminalOutboxService.writeTerminalEvent(currentRun, status, finishedAt);
    } else {
      log.debug(
          "workflow node completion finalization lost CAS: workflowRunId={}", currentRun.getId());
    }
  }
}
