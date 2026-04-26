package com.example.batch.orchestrator.infrastructure.router;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.application.route.WorkerRouter;
import com.example.batch.orchestrator.application.route.WorkerRoutingPolicy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 默认 Worker 路由器实现。
 *
 * <p>根据租户 ID、作业编码和步骤编码构造一个 {@link WorkerRouteModel} 候选路由（ workerCode 格式为 {@code
 * tenantId:jobCode:stepCode}，workerType 取 stepCode 大写或 DEFAULT）， 再委托 {@link
 * WorkerRoutingPolicy#select(java.util.List)} 从候选列表中选出最终路由。 若策略返回 {@code
 * null}，则回退使用原始构造的候选路由，保证始终返回非空结果。
 */
@Component
@RequiredArgsConstructor
public class DefaultWorkerRouter implements WorkerRouter {

  private final WorkerRoutingPolicy workerRoutingPolicy;

  @Override
  public WorkerRouteModel route(String tenantId, String jobCode, String stepCode) {
    WorkerRouteModel route = new WorkerRouteModel();
    route.setWorkerCode(tenantId + ":" + jobCode + ":" + stepCode);
    route.setWorkerType(
        stepCode == null || stepCode.isBlank() ? "DEFAULT" : stepCode.toUpperCase());
    route.setPriority(5);
    route.setAvailable(true);
    WorkerRouteModel selected = workerRoutingPolicy.select(List.of(route));
    return selected == null ? route : selected;
  }
}
