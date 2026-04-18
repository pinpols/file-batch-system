package com.example.batch.orchestrator.application.service;

import java.util.List;

/**
 * 工作流 DAG 拓扑服务接口，负责解析工作流定义中的节点顺序与边依赖关系，
 * 为编排引擎提供"下一步应分发哪些节点"的决策支持。
 *
 * <p>核心能力包括：查找 DAG 入口节点（{@link #resolveInitialNodes}）、
 * 根据当前节点执行结果（成功/失败）解析后继节点（{@link #resolveNextNodes}），
 * 以及判断 JOIN/聚合节点的所有前驱是否已完成（{@link #isNodeReadyForDispatch}）。
 * 提供单节点快捷方法（{@link #resolveInitialNode}、{@link #resolveNextNode}）以简化线性流程调用。
 *
 * <p>解析结果以 {@link DagNodeResolution} record 返回，携带节点编码和节点类型，
 * 供 {@link WorkflowNodeDispatchService} 据此选择对应的分发策略。
 */
public interface WorkflowDagService {

  List<DagNodeResolution> resolveInitialNodes(Long workflowDefinitionId, String payloadJson);

  List<DagNodeResolution> resolveNextNodes(
      Long workflowDefinitionId, String currentNodeCode, boolean success, String payloadJson);

  boolean isNodeReadyForDispatch(
      Long workflowRunId, Long workflowDefinitionId, String nodeCode, String payloadJson);

  default DagNodeResolution resolveInitialNode(Long workflowDefinitionId) {
    List<DagNodeResolution> nodes = resolveInitialNodes(workflowDefinitionId, null);
    return nodes == null || nodes.isEmpty() ? null : nodes.get(0);
  }

  default DagNodeResolution resolveNextNode(
      Long workflowDefinitionId, String currentNodeCode, boolean success) {
    List<DagNodeResolution> nodes =
        resolveNextNodes(workflowDefinitionId, currentNodeCode, success, null);
    return nodes == null || nodes.isEmpty() ? null : nodes.get(0);
  }

  record DagNodeResolution(String nodeCode, String nodeType) {}
}
