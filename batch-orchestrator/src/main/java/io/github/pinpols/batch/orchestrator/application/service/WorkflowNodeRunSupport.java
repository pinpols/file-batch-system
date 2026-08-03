package io.github.pinpols.batch.orchestrator.application.service;

import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;

/** Shared persistence helpers for creating workflow node-run records. */
public final class WorkflowNodeRunSupport {

  private WorkflowNodeRunSupport() {}

  public static void recordReady(
      WorkflowNodeRunMapper mapper, Long workflowRunId, String nodeCode, String nodeType) {
    WorkflowNodeRunEntity readyNode = new WorkflowNodeRunEntity();
    readyNode.setWorkflowRunId(workflowRunId);
    readyNode.setNodeCode(nodeCode);
    readyNode.setNodeType(nodeType);
    readyNode.setRunSeq(nextRunSeq(mapper, workflowRunId, nodeCode));
    readyNode.setNodeStatus(WorkflowNodeRunStatus.READY.code());
    readyNode.setRetryCount(0);
    readyNode.setDurationMs(0L);
    mapper.insert(readyNode);
  }

  public static int nextRunSeq(WorkflowNodeRunMapper mapper, Long workflowRunId, String nodeCode) {
    WorkflowNodeRunEntity latestNodeRun =
        mapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
    return latestNodeRun == null || latestNodeRun.getRunSeq() == null
        ? 1
        : latestNodeRun.getRunSeq() + 1;
  }
}
