package com.example.batch.orchestrator.application.plan;

/**
 * 调度计划构建器：根据 {@link SchedulePlanCommand} 解析作业定义、分区策略和 Worker 路由， 生成包含各分区路由信息的 {@link
 * SchedulePlan}，供调度引擎直接消费。
 */
public interface SchedulePlanBuilder {

  SchedulePlan build(SchedulePlanCommand command);
}
