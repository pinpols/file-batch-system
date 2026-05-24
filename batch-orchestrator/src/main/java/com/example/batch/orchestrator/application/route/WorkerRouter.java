package com.example.batch.orchestrator.application.route;

import com.example.batch.common.model.WorkerRouteModel;

/**
 * Worker 路由器。 根据租户、作业编码和步骤编码从在线 Worker 列表中选取最优的执行节点。 实现类须结合 Worker
 * 能力标签、负载和亲和性策略返回路由结果，找不到可用节点时抛出异常。
 *
 * <p>TODO(needs-manual-review): 审计 (2026-05-23) 标记为单实现接口候选删除。 路由策略可能扩展（亲和性 / SLA 优先级等），
 * 保留接口为未来多态留口子，待人工评估实际扩展路径后再决定是否合并。
 */
public interface WorkerRouter {

  WorkerRouteModel route(String tenantId, String jobCode, String stepCode);
}
