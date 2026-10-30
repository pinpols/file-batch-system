package io.github.pinpols.batch.orchestrator.application.service;

import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;

/** 用于创建 Workflow 节点运行记录的共享持久化辅助方法。 */
public final class WorkflowNodeRunSupport {

  private WorkflowNodeRunSupport() {}

  public static WorkflowNodeRunEntity recordReady(
      WorkflowNodeRunMapper mapper, Long workflowRunId, String nodeCode, String nodeType) {
    return recordReady(mapper, workflowRunId, nodeCode, nodeType, null);
  }

  public static WorkflowNodeRunEntity recordReady(
      WorkflowNodeRunMapper mapper,
      Long workflowRunId,
      String nodeCode,
      String nodeType,
      String sourcePayloadProtected) {
    WorkflowNodeRunEntity readyNode = new WorkflowNodeRunEntity();
    readyNode.setWorkflowRunId(workflowRunId);
    readyNode.setNodeCode(nodeCode);
    readyNode.setNodeType(nodeType);
    readyNode.setRunSeq(nextRunSeq(mapper, workflowRunId, nodeCode));
    readyNode.setNodeStatus(WorkflowNodeRunStatus.READY.code());
    readyNode.setRetryCount(0);
    readyNode.setDurationMs(0L);
    readyNode.setSourcePayloadProtected(sourcePayloadProtected);
    mapper.insert(readyNode);
    return readyNode;
  }

  /** 原子领取 DAG 节点的首次激活权；并发派发方会得到 null。 */
  public static WorkflowNodeRunEntity recordReadyIfAbsent(
      WorkflowNodeRunMapper mapper, Long workflowRunId, String nodeCode, String nodeType) {
    return recordReadyIfAbsent(mapper, workflowRunId, nodeCode, nodeType, null);
  }

  /** 节点激活时保存受保护的来源载荷，使后续节点只继承 Workflow 输入。 */
  public static WorkflowNodeRunEntity recordReadyIfAbsent(
      WorkflowNodeRunMapper mapper,
      Long workflowRunId,
      String nodeCode,
      String nodeType,
      String sourcePayloadProtected) {
    WorkflowNodeRunEntity readyNode = new WorkflowNodeRunEntity();
    readyNode.setWorkflowRunId(workflowRunId);
    readyNode.setNodeCode(nodeCode);
    readyNode.setNodeType(nodeType);
    readyNode.setRunSeq(nextRunSeq(mapper, workflowRunId, nodeCode));
    readyNode.setNodeStatus(WorkflowNodeRunStatus.READY.code());
    readyNode.setRetryCount(0);
    readyNode.setDurationMs(0L);
    readyNode.setSourcePayloadProtected(sourcePayloadProtected);
    return mapper.insertIfAbsent(readyNode) == 1 ? readyNode : null;
  }

  public static int nextRunSeq(WorkflowNodeRunMapper mapper, Long workflowRunId, String nodeCode) {
    WorkflowNodeRunEntity latestNodeRun =
        mapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
    return latestNodeRun == null || latestNodeRun.getRunSeq() == null
        ? 1
        : latestNodeRun.getRunSeq() + 1;
  }
}
