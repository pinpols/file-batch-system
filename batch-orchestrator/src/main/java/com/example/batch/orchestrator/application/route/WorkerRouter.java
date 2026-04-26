package com.example.batch.orchestrator.application.route;

import com.example.batch.common.model.WorkerRouteModel;

/**
 * Worker 路由器。 根据租户、作业编码和步骤编码从在线 Worker 列表中选取最优的执行节点。 实现类须结合 Worker
 * 能力标签、负载和亲和性策略返回路由结果，找不到可用节点时抛出异常。
 */
public interface WorkerRouter {

  WorkerRouteModel route(String tenantId, String jobCode, String stepCode);
}
