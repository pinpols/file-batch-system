package com.example.batch.orchestrator.application.workflow;

import java.util.Map;

/**
 * ADR-009 Stage 2: WorkflowParamResolver 解析时的上下文,提供:
 *
 * <ul>
 *   <li>{@code $.nodes.<X>.output.<key>} 引用按 nodeCode 查 output Map(已反序列化)
 *   <li>{@code $.workflowRun.<key>} 引用查 workflow 级共享字段(bizDate / batchNo / traceId 等)
 * </ul>
 *
 * <p>实现方按 workflow_run + workflow_node_run 加载并填充。orchestrator 主链路在
 * DefaultWorkflowNodeDispatchService 派发前临时构造,不持久化。
 */
public interface WorkflowRunContext {

  /** 是否含某节点(已经 RUNNING/SUCCESS/FAILED 终态过的 node code 都视为已知)。 */
  boolean hasNode(String nodeCode);

  /**
   * 取某节点 output(由 worker 上报后由 orchestrator 反序列化为 Map)。节点已知但未上报 output 时返回 null。 调用前应先 {@link
   * #hasNode(String)} 拦截未知 node。
   */
  Map<String, Object> nodeOutput(String nodeCode);

  /** workflow 级共享字段。 */
  Map<String, Object> workflowRunFields();
}
