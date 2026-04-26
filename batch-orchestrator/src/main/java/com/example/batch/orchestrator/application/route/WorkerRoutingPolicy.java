package com.example.batch.orchestrator.application.route;

import com.example.batch.common.model.WorkerRouteModel;
import java.util.List;

/**
 * Worker 路由策略接口。 从候选 Worker 列表中按策略（轮询、随机、最少连接等）选出目标节点。 实现类须保证在候选列表非空时必然返回一个结果，候选为空时返回 {@code null}
 * 或抛出异常由调用方处理。
 */
public interface WorkerRoutingPolicy {

  WorkerRouteModel select(List<WorkerRouteModel> candidates);
}
