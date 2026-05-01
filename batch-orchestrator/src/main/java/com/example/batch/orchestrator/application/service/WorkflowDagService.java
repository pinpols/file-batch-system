package com.example.batch.orchestrator.application.service;

import java.util.List;

/**
 * 工作流 DAG 拓扑服务接口，负责解析工作流定义中的节点顺序与边依赖关系， 为编排引擎提供"下一步应分发哪些节点"的决策支持。
 *
 * <p>核心能力包括：查找 DAG 入口节点（{@link #resolveInitialNodes}）、 根据当前节点执行结果（成功/失败）解析后继节点（{@link
 * #resolveNextNodes}）， 以及判断 JOIN/聚合节点的所有前驱是否已完成（{@link #isNodeReadyForDispatch}）。 提供单节点快捷方法（{@link
 * #resolveInitialNode}、{@link #resolveNextNode}）以简化线性流程调用。
 *
 * <p>解析结果以 {@link DagNodeResolution} record 返回，携带节点编码和节点类型， 供 {@link WorkflowNodeDispatchService}
 * 据此选择对应的分发策略。
 */
public interface WorkflowDagService {

  List<DagNodeResolution> resolveInitialNodes(Long workflowDefinitionId, String payloadJson);

  List<DagNodeResolution> resolveNextNodes(
      Long workflowDefinitionId, String currentNodeCode, boolean success, String payloadJson);

  boolean isNodeReadyForDispatch(
      Long workflowRunId, Long workflowDefinitionId, String nodeCode, String payloadJson);

  /**
   * SKIP 级联：从 {@code fromNodeCode}（FAILED 或 SKIPPED 节点）出发，沿 SUCCESS / CONDITION 出边 寻找无机会再触发的下游节点，写入
   * {@code SKIPPED} 的 {@code workflow_node_run} 行并继续递归级联。
   *
   * <p>"无机会触发"判定：节点的所有入边对应的前驱 node_run 已达终态且没有任何一条入边匹配（即 {@code matchedCount == 0 && terminalCount
   * == size}）。该判定对 ALL / ANY / N_OF 三种 join 模式都成立——任意 join 至少需要 1 条匹配。
   *
   * <p>不级联跨 ALWAYS / FAILURE 出边的下游：那些路径仍可正常派发，由 outcome 主路径推进。
   *
   * @return 本次实际新写 SKIPPED 行的 nodeCode 列表（用于日志/监控；为空表示无级联）
   */
  List<String> cascadeSkipDownstream(
      Long workflowRunId, Long workflowDefinitionId, String fromNodeCode);

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
