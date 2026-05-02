package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.orchestrator.application.plan.SchedulePlan;

/**
 * 工作流编排服务。 接收调度计划并驱动工作流实例的启动与节点推进，协调 DAG/Pipeline/Mixed 三种执行模式。 实现类须保证节点状态变更与 outbox
 * 事件写入在同一事务内完成，Orchestrator 是唯一状态主机。
 */
public interface WorkflowOrchestrationService {

  void submit(SchedulePlan plan);
}
