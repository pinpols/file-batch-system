package com.example.batch.orchestrator.application.service;

import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;

/**
 * 工作流节点分发服务接口，负责将 DAG 中解析出的单个节点派发为可执行任务。
 *
 * <p>分发时需同时提供作业实例（{@link JobInstanceEntity}）、工作流运行实例（{@link WorkflowRunEntity}）、 节点解析结果（{@link
 * WorkflowDagService.DagNodeResolution}）、上游载荷（sourcePayload） 及追踪 ID（traceId），以确保任务携带完整的上下文信息。
 * 返回值为实际分发的任务数量，调用方可据此判断节点是否已成功入队。
 *
 * <p>该接口是编排引擎推进工作流的核心扩展点，不同节点类型（任务节点、网关节点等） 可由各自的实现类处理，分发后的任务将进入后续的 CLAIM → EXECUTE → REPORT 主链路。
 */
public interface WorkflowNodeDispatchService {

  int dispatchNode(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      String sourcePayload,
      String traceId);
}
