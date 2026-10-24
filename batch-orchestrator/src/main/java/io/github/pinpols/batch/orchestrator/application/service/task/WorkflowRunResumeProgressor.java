package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.engine.CountContinuityOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeTerminalFinalizer;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Replays idempotent DAG progression after a WorkflowRun is resumed from PAUSED. */
@Component
@RequiredArgsConstructor
public class WorkflowRunResumeProgressor {

  private final WorkflowRunMapper workflowRunMapper;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final WorkflowDagService workflowDagService;
  private final WorkflowNodeDispatchService workflowNodeDispatchService;
  private final TaskOutcomeNodeRunRecorder nodeRunRecorder;
  private final CountContinuityOutboxService countContinuityOutboxService;
  private final SecretPayloadProtector secretPayloadProtector;
  private final WorkflowNodeTerminalFinalizer terminalFinalizer;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void resume(String tenantId, Long workflowRunId) {
    WorkflowRunEntity run = workflowRunMapper.selectById(tenantId, workflowRunId);
    if (EmptyChecks.isNull(run)) {
      return;
    }
    Map<String, WorkflowNodeRunEntity> latestRuns = new HashMap<>();
    List<WorkflowNodeRunEntity> nodeRuns =
        workflowMappers.workflowNodeRunMapper.selectByWorkflowRunId(workflowRunId);
    for (WorkflowNodeRunEntity nodeRun : nodeRuns) {
      latestRuns.merge(
          nodeRun.getNodeCode(),
          nodeRun,
          (left, right) -> left.getRunSeq() >= right.getRunSeq() ? left : right);
    }
    for (WorkflowNodeRunEntity source : List.copyOf(latestRuns.values())) {
      if (!WorkflowNodeRunStatus.SUCCESS.code().equals(source.getNodeStatus())) {
        continue;
      }
      String sourcePayload = EmptyChecks.isNull(source.getSourcePayloadProtected())
          ? null
          : secretPayloadProtector.reveal(source.getSourcePayloadProtected());
      List<WorkflowDagService.DagNodeResolution> nextNodes =
          workflowDagService.resolveNextNodesForRun(run, source.getNodeCode(), true, sourcePayload);
      for (WorkflowDagService.DagNodeResolution next : nextNodes) {
        if (EmptyChecks.isNull(next)
            || isTerminal(latestRuns.get(next.nodeCode()))
            || !workflowDagService.isNodeReadyForRunDispatch(run, next.nodeCode(), sourcePayload)) {
          continue;
        }
        if (WorkflowNodeCode.END.code().equals(next.nodeCode())) {
          completeEndIfMissing(run, next, latestRuns);
        } else {
          workflowNodeDispatchService.dispatchNode(
              null, run, next, sourcePayload, run.getTraceId());
        }
      }
    }
    terminalFinalizer.finalizeIfAllNodesTerminal(run);
  }

  private boolean isTerminal(WorkflowNodeRunEntity nodeRun) {
    if (EmptyChecks.isNull(nodeRun)) {
      return false;
    }
    String status = nodeRun.getNodeStatus();
    return WorkflowNodeRunStatus.SUCCESS.code().equals(status)
        || WorkflowNodeRunStatus.FAILED.code().equals(status)
        || WorkflowNodeRunStatus.SKIPPED.code().equals(status);
  }

  private void completeEndIfMissing(
      WorkflowRunEntity run,
      WorkflowDagService.DagNodeResolution end,
      Map<String, WorkflowNodeRunEntity> latestRuns) {
    WorkflowNodeRunEntity existing = latestRuns.get(end.nodeCode());
    if (EmptyChecks.isNotNull(existing)
        && (WorkflowNodeRunStatus.READY.code().equals(existing.getNodeStatus())
            || WorkflowNodeRunStatus.RUNNING.code().equals(existing.getNodeStatus())
            || WorkflowNodeRunStatus.SUCCESS.code().equals(existing.getNodeStatus()))) {
      return;
    }
    Instant now = BatchDateTimeSupport.utcNow();
    nodeRunRecorder.recordStart(run.getId(), end.nodeCode(), end.nodeType(), now);
    nodeRunRecorder.recordFinish(TaskOutcomeService.NodeRunFinishCommand.of(
        new TaskOutcomeService.NodeRunKey(run.getId(), end.nodeCode(), end.nodeType()),
        TaskOutcomeService.NodeRunOutcome.builder()
            .success(true)
            .startedAt(now)
            .finishedAt(now)
            .build()));
    countContinuityOutboxService.checkContinuity(run.getId(), end.nodeCode(), null);
    latestRuns.put(end.nodeCode(), new WorkflowNodeRunEntity());
  }
}
