package com.example.batch.orchestrator.application.route;

import com.example.batch.common.model.WorkerRouteModel;
import java.util.List;

/**
 * Worker 路由策略接口。 从候选 Worker 列表中按策略（轮询、随机、最少连接等）选出目标节点。 实现类须保证在候选列表非空时必然返回一个结果，候选为空时返回 {@code null}
 * 或抛出异常由调用方处理。
 *
 * <p>TODO(needs-manual-review): 审计 (2026-05-23) 标记为单实现接口候选删除。 但策略本身天然支持多种实现（轮询/随机/最少连接等），
 * 当前只落地一种不代表无扩展规划，保留接口待人工评估扩展路线图后再决定。
 */
public interface WorkerRoutingPolicy {

  WorkerRouteModel select(List<WorkerRouteModel> candidates);
}
